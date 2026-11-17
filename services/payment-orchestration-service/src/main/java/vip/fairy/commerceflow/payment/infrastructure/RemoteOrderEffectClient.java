package vip.fairy.commerceflow.payment.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Component
public class RemoteOrderEffectClient {

  private static final String QUERY = "/internal/v1/orders/store/query";
  private static final String MUTATIONS = "/internal/v1/orders/store/mutations";

  private final RestClient client;
  private final ObjectMapper mapper;
  private final DownstreamRequestSigner signer;
  private final String baseUrl;

  public RemoteOrderEffectClient(ObjectMapper mapper, DownstreamRequestSigner signer,
                                 @Value("${commerceflow.order.remote.base-url:http://localhost:18089}")
                                 String baseUrl) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.client = RestClient.builder().requestFactory(factory).build();
    this.mapper = mapper;
    this.signer = signer;
    this.baseUrl = baseUrl;
  }

  public OrderView order(Long orderId) {
    QueryResult result = post(QUERY, new Query("FIND_BY_ID", null, null, orderId, null),
      QueryResult.class);
    if (!result.found() || result.order() == null) {
      throw new IllegalStateException("Order was not found: " + orderId);
    }
    return result.order();
  }

  public int status(String mutationId, Long orderId, String from, String to) {
    return mutate(new Mutation(mutationId, "UPDATE_STATUS", orderId, null, from, to, null,
      null, null, null, null, null, null, null));
  }

  public int inventory(String mutationId, Long orderId, String from, String to) {
    return mutate(new Mutation(mutationId, "UPDATE_INVENTORY_STATUS", orderId, null, from, to,
      null, null, null, null, null, null, null, null));
  }

  public int startInventoryCommit(String mutationId, Long orderId) {
    return mutate(new Mutation(mutationId, "START_INVENTORY_COMMIT", orderId, null, null, null,
      null, null, null, null, null, null, null, null));
  }

  public int restore(String mutationId, Long orderId, boolean cancelled) {
    String type = cancelled ? "RESTORE_PAID_LATE_SUCCESS" : "RESTORE_PAID_CANCELLATION_RACE";
    return mutate(new Mutation(mutationId, type, orderId, null, null, null, null, null, null,
      null, null, null, null, null));
  }

  public int event(String mutationId, Long orderId, Long actorId, String eventType,
                   String from, String to, String detail) {
    return mutate(new Mutation(mutationId, "INSERT_EVENT", orderId, actorId, from, to, null,
      null, null, eventType, detail, "PAYMENT_ORCHESTRATION", null, null));
  }

  private int mutate(Mutation mutation) {
    return post(MUTATIONS, mutation, MutationResult.class).changed();
  }

  private <T> T post(String target, Object body, Class<T> responseType) {
    String payload = mapper.writeValueAsString(body);
    T response = client.post().uri(URI.create(baseUrl + target)).contentType(MediaType.APPLICATION_JSON)
      .headers(headers -> signer.signOrder(headers, target, payload)).body(payload).retrieve()
      .body(responseType);
    if (response == null) throw new IllegalStateException("Order service returned no response");
    return response;
  }

  private record Query(String type, Long customerId, Long merchantId, Long orderId, Long itemId) {
  }

  private record QueryResult(boolean found, String status, Long customerId, OrderView order) {
  }

  public record OrderView(Long id, String orderNumber, Long customerId, String status,
                          String inventoryStatus) {
  }

  private record Mutation(String mutationId, String type, Long orderId, Long actorId,
                          String fromStatus, String toStatus, String reason, String carrier,
                          String trackingNumber, String eventType, String detail,
                          String triggerSource, Long scheduledTaskId, Instant occurredAt) {
  }

  private record MutationResult(int changed) {
  }
}
