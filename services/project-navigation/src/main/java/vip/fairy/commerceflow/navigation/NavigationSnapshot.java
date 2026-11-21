package vip.fairy.commerceflow.navigation;

import java.time.Instant;
import java.util.List;

record NavigationSnapshot(
    String project,
    boolean dockerConnected,
    Instant collectedAt,
    Instant attemptedAt,
    String message,
    List<Service> services
) {

  record Service(
      String id, String name, String group, String kind, String status, List<String> profiles,
      String schema, List<Address> addresses, List<Link> links, List<Instance> instances, List<Probe> probes
  ) {
  }

  record Address(String protocol, String scope, String value) {
  }

  record Link(String label, String url, String role, boolean browserLaunch) {
  }

  record Instance(String name, String state, String health, int exitCode, int restarts,
                  boolean oomKilled, String startedAt, String finishedAt) {
  }

  record Probe(String address, String status, String detail, long durationMs) {
  }
}
