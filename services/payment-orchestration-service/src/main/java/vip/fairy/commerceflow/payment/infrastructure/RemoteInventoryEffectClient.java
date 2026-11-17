package vip.fairy.commerceflow.payment.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Component
public class RemoteInventoryEffectClient {

  private static final String TARGET = "/internal/v1/inventory/commands";

  private final RestClient client;
  private final ObjectMapper mapper;
  private final DownstreamRequestSigner signer;
  private final String baseUrl;

  public RemoteInventoryEffectClient(
    ObjectMapper mapper,
    DownstreamRequestSigner signer,
    @Value("${commerceflow.inventory.remote.base-url:http://localhost:18088}") String baseUrl
  ) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.client = RestClient.builder().requestFactory(factory).build();
    this.mapper = mapper;
    this.signer = signer;
    this.baseUrl = baseUrl;
  }

  public void commit(String commandId, Long orderId, String causationId, String traceparent) {
    send(new Command(1, commandId, "COMMIT", Instant.now(), "payment-orchestration-service",
      orderId, List.of(), "Payment succeeded", "payment-" + orderId, causationId,
      traceparent));
  }

  public void refund(String commandId, Long orderId, String causationId, String traceparent) {
    send(new Command(1, commandId, "REFUND", Instant.now(), "payment-orchestration-service",
      orderId, List.of(), "Refund succeeded", "refund-" + orderId, causationId,
      traceparent));
  }

  private void send(Command command) {
    String payload = mapper.writeValueAsString(command);
    Result result = client.post().uri(URI.create(baseUrl + TARGET))
      .contentType(MediaType.APPLICATION_JSON)
      .headers(headers -> signer.signInventory(headers, TARGET, payload))
      .body(payload).retrieve().body(Result.class);
    if (result == null || !command.commandId().equals(result.commandId())
        || !"SUCCEEDED".equals(result.outcome())) {
      throw new IllegalStateException("Inventory service did not accept " + command.commandId());
    }
  }

  private record Command(int schemaVersion, String commandId, String commandType,
                         Instant occurredAt, String producer, Long orderId, List<Object> items,
                         String reason, String correlationId, String causationId,
                         String traceparent) {
  }

  private record Result(String commandId, String outcome) {
  }
}
