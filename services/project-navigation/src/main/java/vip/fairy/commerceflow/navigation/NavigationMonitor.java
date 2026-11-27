package vip.fairy.commerceflow.navigation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static vip.fairy.commerceflow.navigation.NavigationSnapshot.*;
import static vip.fairy.commerceflow.navigation.NavigationCatalog.NotificationRole.*;

@Component
class NavigationMonitor {

  private final ComposeClient compose;
  private final NavigationCatalog catalog;
  private final EndpointProbe probe;
  private volatile NavigationSnapshot snapshot;

  NavigationMonitor(ComposeClient compose, NavigationCatalog catalog, EndpointProbe probe) {
    this.compose = compose;
    this.catalog = catalog;
    this.probe = probe;
    this.snapshot = new NavigationSnapshot(compose.project(), false, null, null, "正在等待首次采集", List.of());
  }

  NavigationSnapshot snapshot() {
    return snapshot;
  }

  @Scheduled(fixedDelay = 5000)
  void refresh() {
    JsonNode configuration = null;
    try {
      configuration = compose.configuration();
      List<JsonNode> containers = compose.containers();
      List<NavigationCatalog.Entry> entries = catalog.entries(configuration, containers);
      List<Service> services;
      try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var work = entries.stream()
            .map(entry -> CompletableFuture.supplyAsync(() -> service(entry, containers, true), workers))
            .toList();
        services = work.stream().map(CompletableFuture::join).toList();
      }
      Instant now = Instant.now();
      snapshot = new NavigationSnapshot(compose.project(), true, now, now, "", services);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException exception) {
      List<Service> previous = snapshot.services();
      if (previous.isEmpty() && configuration != null) {
        previous = catalog.entries(configuration, List.of()).stream()
            .map(entry -> service(entry, List.of(), false)).toList();
      }
      snapshot = new NavigationSnapshot(compose.project(), false, snapshot.collectedAt(), Instant.now(),
          "无法读取 Compose 当前状态，请检查 Docker 和项目配置。", previous);
    }
  }

  private Service service(NavigationCatalog.Entry entry, List<JsonNode> all, boolean collected) {
    List<JsonNode> containers = all.stream()
        .filter(container -> entry.id().equals(container.path("service").asString()))
        .sorted(Comparator.comparing(container -> container.path("name").asString("")))
        .toList();
    List<Instance> instances = containers.stream().map(container -> instance(container, entry.healthcheck())).toList();
    var addresses = new LinkedHashSet<Address>();
    var links = new LinkedHashSet<Link>();
    List<Probe> probes = new ArrayList<>();
    JsonNode metadataPorts = entry.metadata().path("ports");
    Map<Integer, JsonNode> ports = new LinkedHashMap<>();
    for (JsonNode port : entry.ports()) {
      int target = port.path("target").asInt();
      if (target > 0 && "tcp".equals(port.path("protocol").asString("tcp"))) {
        ports.put(target, port);
      }
    }
    for (String port : metadataPorts.propertyNames()) {
      ports.putIfAbsent(Integer.parseInt(port), null);
    }

    ports.forEach((target, port) -> {
      JsonNode metadata = metadataPorts.path(Integer.toString(target));
      String protocol = metadata.path("protocol").asString("TCP");
      addresses.add(new Address(protocol, "INTERNAL", entry.id() + ":" + target));
      String alias = metadata.path("alias").asString("");
      if (!alias.isBlank() && containers.stream().anyMatch(container ->
          "running".equals(container.path("state").asString()) && hasAlias(container, alias))) {
        addresses.add(new Address(protocol, "INTERNAL", alias + ":" + target));
      }
      var bindings = new LinkedHashSet<Binding>();
      for (JsonNode container : containers) {
        if ("running".equals(container.path("state").asString())) {
          for (JsonNode binding : container.path("ports").path(target + "/tcp")) {
            int published = binding.path("HostPort").asInt(0);
            if (published > 0) {
              bindings.add(new Binding(localHost(binding.path("HostIp").asString("")), published));
            }
          }
        }
      }
      var publishedBindings = bindings.isEmpty() && port != null && port.path("published").asInt(0) > 0
          ? List.of(new Binding(localHost(port.path("host_ip").asString("")), port.path("published").asInt()))
          : List.copyOf(bindings);
      String probePath = metadata.path("probe").asString("");
      for (Binding binding : publishedBindings) {
        String address = EndpointProbe.hostPort(binding.host(), binding.port());
        String base = "HTTP".equals(protocol) ? "http://" + address : address;
        addresses.add(new Address(protocol, "HOST", base));
        for (JsonNode link : metadata.path("links")) {
          links.add(new Link(link.path("label").asString(), base + link.path("path").asString(),
              link.path("role").asString(""), link.path("browserLaunch").asBoolean(false)));
        }
        if (bindings.contains(binding)) {
          probes.add(probe.check(protocol, binding.host(), binding.port(), probePath,
              probePath.startsWith("/actuator/health")));
        }
      }
    });

    if ("PostgreSQL".equals(metadataPorts.path("5432").path("protocol").asString())) {
      for (Instance instance : instances) {
        if (instance.health().equals("healthy") || instance.health().equals("unhealthy")) {
          probes.add(new Probe(instance.name(), instance.health().equals("healthy") ? "REACHABLE" : "FAILED",
              "PostgreSQL 容器健康检查", null));
        }
      }
    }
    String status = collected ? status(entry, instances, probes) : "UNKNOWN";
    return new Service(entry.id(), entry.name(), entry.group(), entry.task() ? "TASK" : "SERVICE", status,
        entry.profiles(), entry.schema(), List.copyOf(addresses), List.copyOf(links), instances, List.copyOf(probes));
  }

  private static String localHost(String ip) {
    return switch (ip) {
      case "", "0.0.0.0", "::" -> "127.0.0.1";
      default -> ip;
    };
  }

  private static boolean hasAlias(JsonNode container, String alias) {
    for (JsonNode network : container.path("networks")) {
      for (JsonNode name : network.path("Aliases")) {
        if (alias.equals(name.asString())) {
          return true;
        }
      }
    }
    return false;
  }

  private static Instance instance(JsonNode node, boolean healthcheck) {
    String state = node.path("state").asString("");
    String health = node.path("health").asString("");
    return new Instance(node.path("name").asString("").replaceFirst("^/", ""), state,
        health.isEmpty() ? (healthcheck ? "pending" : "none") : health,
        node.path("exitCode").asInt(0), node.path("restarts").asInt(0), node.path("oomKilled").asBoolean(false),
        timestamp(node.path("startedAt")), timestamp(node.path("finishedAt")));
  }

  private static String timestamp(JsonNode node) {
    String value = node.asString("");
    return value.startsWith("0001-") ? "" : value;
  }

  private static String status(NavigationCatalog.Entry entry, List<Instance> instances, List<Probe> probes) {
    if (entry.notificationRole() == ROLLBACK_DB && !instances.isEmpty()) {
      return "ROLLBACK_RETAINED";
    }
    if (entry.notificationRole() == ROLLBACK_APP && !instances.isEmpty()) {
      return instances.stream().allMatch(item -> List.of("created", "exited").contains(item.state()))
          ? "ROLLBACK_RETAINED" : "UNEXPECTED_ACTIVE";
    }
    if (entry.notificationRole() == DISABLED_APP
        && instances.stream().anyMatch(item -> List.of("running", "restarting", "paused").contains(item.state()))) {
      return "UNEXPECTED_ACTIVE";
    }
    if (instances.isEmpty()) {
      if (entry.notificationRole() == ACTIVE) {
        return "MISSING";
      }
      return entry.task() ? "TASK_PENDING" : entry.profiles().isEmpty() ? "NOT_CREATED" : "PROFILE_INACTIVE";
    }
    if (entry.task()) {
      if (instances.stream().anyMatch(item -> item.oomKilled() || item.state().equals("dead")
          || item.state().equals("exited") && item.exitCode() != 0)) {
        return "TASK_FAILED";
      }
      if (instances.stream().allMatch(item -> item.state().equals("exited") && item.exitCode() == 0)) {
        return "TASK_DONE";
      }
      return instances.stream().allMatch(item -> item.state().equals("created")) ? "TASK_PENDING" : "TASK_RUNNING";
    }
    if (instances.stream().anyMatch(item -> item.state().equals("restarting"))) {
      return "RESTARTING";
    }
    if (instances.stream().anyMatch(item -> item.state().equals("dead") || item.oomKilled()
        || item.state().equals("exited") && item.exitCode() != 0)) {
      return "FAILED";
    }
    if (instances.stream().allMatch(item -> item.state().equals("exited"))) {
      return "STOPPED";
    }
    if (instances.stream().anyMatch(item -> item.state().equals("paused"))) {
      return "PAUSED";
    }
    if (instances.stream().anyMatch(item -> item.health().equals("unhealthy"))) {
      return "UNHEALTHY";
    }
    if (instances.stream().anyMatch(item -> item.state().equals("created") || item.health().equals("starting"))) {
      return "STARTING";
    }
    if (instances.stream().anyMatch(item -> !item.state().equals("running"))) {
      return "DEGRADED";
    }
    if (probes.stream().anyMatch(item -> List.of("FAILED", "TIMEOUT", "UNREACHABLE").contains(item.status()))) {
      return "UNREACHABLE";
    }
    if (instances.stream().allMatch(item -> item.health().equals("healthy"))
        || instances.size() == 1 && probes.stream().anyMatch(item -> item.address().startsWith("http://"))) {
      return "READY";
    }
    return "RUNNING";
  }

  private record Binding(String host, int port) {
  }
}
