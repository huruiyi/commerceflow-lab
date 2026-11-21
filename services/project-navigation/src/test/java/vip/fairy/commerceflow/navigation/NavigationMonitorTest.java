package vip.fairy.commerceflow.navigation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationMonitorTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void keepsTheLastSnapshotDuringDockerDisconnectionAndRecovers() throws IOException {
    var compose = new FixtureCompose(mapper);
    var monitor = new NavigationMonitor(compose, new NavigationCatalog(mapper), new EndpointProbe(mapper));
    monitor.refresh();
    var initial = monitor.snapshot();
    assertThat(initial.dockerConnected()).isTrue();
    assertThat(initial.services()).hasSize(1);
    assertThat(initial.services().getFirst().status()).isEqualTo("READY");

    compose.connected = false;
    monitor.refresh();
    var disconnected = monitor.snapshot();
    assertThat(disconnected.dockerConnected()).isFalse();
    assertThat(disconnected.collectedAt()).isEqualTo(initial.collectedAt());
    assertThat(disconnected.services()).isEqualTo(initial.services());

    compose.connected = true;
    compose.state = "exited";
    monitor.refresh();
    var recovered = monitor.snapshot();
    assertThat(recovered.dockerConnected()).isTrue();
    assertThat(recovered.collectedAt()).isAfter(initial.collectedAt());
    assertThat(recovered.services().getFirst().status()).isEqualTo("FAILED");
    assertThat(recovered.services().getFirst().instances().getFirst().exitCode()).isEqualTo(2);
  }

  @Test
  void keepsConfiguredNavigationWhenTheFirstDockerAttemptFails() throws IOException {
    var compose = new FixtureCompose(mapper);
    compose.connected = false;
    var monitor = new NavigationMonitor(compose, new NavigationCatalog(mapper), new EndpointProbe(mapper));
    monitor.refresh();
    assertThat(monitor.snapshot().dockerConnected()).isFalse();
    assertThat(monitor.snapshot().collectedAt()).isNull();
    var service = monitor.snapshot().services().getFirst();
    assertThat(service.status()).isEqualTo("UNKNOWN");
    assertThat(service.addresses()).containsExactly(new NavigationSnapshot.Address(
        "MySQL", "INTERNAL", "order-db:3306"
    ));
  }

  private static class FixtureCompose extends ComposeClient {

    private final ObjectMapper mapper;
    private boolean connected = true;
    private String state = "running";

    FixtureCompose(ObjectMapper mapper) {
      super(".", 15, mapper);
      this.mapper = mapper;
    }

    @Override
    JsonNode configuration() {
      return mapper.readTree("""
          {"services":{"order-db":{"environment":{"MYSQL_DATABASE":"orders"}}}}
          """);
    }

    @Override
    List<JsonNode> containers() throws IOException {
      if (!connected) {
        throw new IOException("Test Docker connection unavailable");
      }
      return List.of(mapper.createObjectNode()
          .put("service", "order-db")
          .put("name", "/fairy-commerceflow-order-db")
          .put("state", state)
          .put("health", "healthy")
          .put("exitCode", state.equals("running") ? 0 : 2));
    }
  }
}
