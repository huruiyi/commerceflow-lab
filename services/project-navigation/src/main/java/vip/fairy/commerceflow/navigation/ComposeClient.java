package vip.fairy.commerceflow.navigation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
class ComposeClient {

  static final String PROJECT = "fairy-commerceflow";

  // Go raw string literals keep the template portable across Unix and Windows process argument parsing.
  private static final String INSPECT_FORMAT = """
      {{json .Name}}|{{json (index .Config.Labels `com.docker.compose.service`)}}|\
      {{json (index .Config.Labels `com.docker.compose.project`)}}|\
      {{json .State.Status}}|{{if .State.Health}}{{json .State.Health.Status}}{{else}}null{{end}}|\
      {{.State.ExitCode}}|{{.RestartCount}}|{{.State.OOMKilled}}|\
      {{json .State.StartedAt}}|{{json .State.FinishedAt}}|{{json .NetworkSettings.Ports}}\
      """;
  private static final List<String> INSPECT_FIELDS = List.of("name", "service", "project", "state", "health",
      "exitCode", "restarts", "oomKilled", "startedAt", "finishedAt", "ports");

  private final Path projectDirectory;
  private final long commandTimeoutSeconds;
  private final ObjectMapper mapper;

  ComposeClient(
      @Value("${commerceflow.navigation.project-directory}") String projectDirectory,
      @Value("${commerceflow.navigation.docker-command-timeout-seconds:15}") long commandTimeoutSeconds,
      ObjectMapper mapper
  ) {
    if (commandTimeoutSeconds <= 0) {
      throw new IllegalArgumentException("Docker command timeout must be positive");
    }
    this.projectDirectory = Path.of(projectDirectory).toAbsolutePath().normalize();
    this.commandTimeoutSeconds = commandTimeoutSeconds;
    this.mapper = mapper;
  }

  JsonNode configuration() throws IOException, InterruptedException {
    return mapper.readTree(compose("config", "--format", "json"));
  }

  List<JsonNode> containers() throws IOException, InterruptedException {
    List<String> ids = compose("ps", "-a", "-q", "--orphans=false").lines()
        .filter(id -> id.matches("[0-9a-f]{12,64}"))
        .toList();
    if (ids.isEmpty()) {
      return List.of();
    }

    List<String> args = new ArrayList<>(List.of(
        "docker", "inspect", "--type", "container", "--format", INSPECT_FORMAT
    ));
    args.addAll(ids);
    List<JsonNode> containers = new ArrayList<>();
    for (String line : run(args).lines().toList()) {
      String[] values = line.split("\\|", -1);
      if (values.length != INSPECT_FIELDS.size()) {
        throw new IOException("Unexpected Docker inspect output");
      }
      var container = mapper.createObjectNode();
      for (int index = 0; index < values.length; index++) {
        container.set(INSPECT_FIELDS.get(index), mapper.readTree(values[index]));
      }
      if (PROJECT.equals(container.path("project").asString())) {
        containers.add(container);
      }
    }
    return containers;
  }

  private String compose(String... command) throws IOException, InterruptedException {
    List<String> args = new ArrayList<>(List.of(
        "docker", "compose", "--project-name", PROJECT, "--project-directory", projectDirectory.toString(),
        "-f", projectDirectory.resolve("compose.yaml").toString(), "--profile", "*"
    ));
    args.addAll(List.of(command));
    return run(args);
  }

  private String run(List<String> args) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(args)
        .directory(projectDirectory.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
    FutureTask<byte[]> output = new FutureTask<>(() -> {
      try (var stream = process.getInputStream()) {
        return stream.readAllBytes();
      }
    });
    Thread.ofVirtual().start(output);
    try {
      if (!process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS)) {
        throw new IOException("Docker command timed out after " + commandTimeoutSeconds + " seconds");
      }
      if (process.exitValue() != 0) {
        throw new IOException("Docker command exited with code " + process.exitValue());
      }
      try {
        return new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);
      } catch (ExecutionException | TimeoutException exception) {
        throw new IOException("Unable to read Docker output", exception);
      }
    } finally {
      output.cancel(true);
      if (process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }
}
