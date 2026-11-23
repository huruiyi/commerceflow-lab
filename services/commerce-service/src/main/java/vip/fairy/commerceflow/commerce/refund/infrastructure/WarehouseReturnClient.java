package vip.fairy.commerceflow.commerce.refund.infrastructure;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.WarehouseEvidence;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.WarehouseSource;

@Component
public class WarehouseReturnClient implements WarehouseSource {

  private static final String RETURN_PATH = "/internal/v1/returns";
  private static final String EVIDENCE_PATH = "/internal/v1/warehouse/reconciliation/query";
  private final RestClient client;
  private final ObjectMapper mapper;
  private final String secret;

  WarehouseReturnClient(ObjectMapper mapper,
                        @Value("${commerceflow.warehouse.return-url:http://warehouse-simulator-service:8080}")
                        String url, @Value("${commerceflow.warehouse.return-secret:}") String secret) {
    this.mapper = mapper;
    this.secret = secret;
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(3));
    this.client = RestClient.builder().requestFactory(factory).baseUrl(url).build();
  }

  public Receipt submit(Plan plan) {
    var reply = signedPost(RETURN_PATH, plan, Receipt.class);
    if (!plan.equals(new Plan(reply.returnId(), reply.outboundId(), reply.orderId(), reply.orderLineId(),
        reply.warehouseId(), reply.trackingNumber(), reply.expectedQuantity()))
        || !validCount(reply) || !validSupplement(reply)) {
      throw new IllegalStateException("Warehouse return receipt does not match the submitted plan");
    }
    return reply;
  }

  @Override
  public Optional<WarehouseEvidence> warehouseEvidence(long merchantId, List<Long> orderIds) {
    try {
      var reply = signedPost(EVIDENCE_PATH, Map.of("merchantId", merchantId, "orderIds", orderIds),
        WarehouseEvidence.class);
      if (reply == null || reply.outbounds() == null || reply.returns() == null
          || reply.outbounds().stream().anyMatch(row ->
            row.merchantId() != merchantId || !orderIds.contains(row.orderId()))
          || reply.returns().stream().anyMatch(row -> !orderIds.contains(row.orderId()))) {
        return Optional.empty();
      }
      return Optional.of(reply);
    } catch (RestClientException | JacksonException | IllegalStateException failure) {
      return Optional.empty();
    }
  }

  private <T> T signedPost(String path, Object command, Class<T> type) {
    String payload = mapper.writeValueAsString(command);
    String nonce = UUID.randomUUID().toString();
    String time = Long.toString(Instant.now().getEpochSecond());
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(payload)));
      String signature = sign(String.join("\n", "POST", path, time, nonce, hash));
      var response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
        .header("X-CommerceFlow-Service", "commerce-service")
        .header("X-CommerceFlow-Timestamp", time)
        .header("X-CommerceFlow-Request-Id", nonce)
        .header("X-CommerceFlow-Content-SHA256", hash)
        .header("X-CommerceFlow-Signature", signature).body(payload).retrieve().toEntity(String.class);
      String json = response.getBody();
      String received = response.getHeaders().getFirst("X-CommerceFlow-Receipt-Signature");
      if (json == null || received == null
          || !MessageDigest.isEqual(bytes(sign(nonce + "\n" + json)), bytes(received))) {
        throw new IllegalStateException("Warehouse return receipt authentication failed");
      }
      return mapper.readValue(json, type);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Warehouse return signing failed", failure);
    }
  }

  private boolean validCount(Receipt receipt) {
    var count = receipt.receivedQuantity();
    return switch (receipt.status()) {
      case "WAITING_RECEIPT" -> count == null && receipt.receivedAt() == null && receipt.inspectedAt() == null;
      case "RECEIVED" -> receipt.receivedAt() != null && Objects.equals(count, receipt.expectedQuantity());
      case "PASSED", "FAILED" -> receipt.receivedAt() != null && receipt.inspectedAt() != null
        && Objects.equals(count, receipt.expectedQuantity());
      case "SHORT_RECEIVED" -> receipt.receivedAt() != null && count != null && count >= 0
        && count < receipt.expectedQuantity();
      case "REJECTED" -> receipt.receivedAt() != null && Objects.equals(count, 0);
      default -> false;
    };
  }

  private boolean validSupplement(Receipt receipt) {
    if (receipt.supplementId() == null) {
      return receipt.qualifiedQuantity() == null && receipt.supplementNote() == null && receipt.supplementAt() == null;
    }
    var quantity = receipt.qualifiedQuantity();
    return ("SHORT_RECEIVED".equals(receipt.status()) || "FAILED".equals(receipt.status()))
      && quantity != null && quantity > 0 && quantity <= receipt.receivedQuantity()
      && receipt.supplementNote() != null && receipt.supplementAt() != null;
  }

  private String sign(String value) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(bytes(secret), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(bytes(value)));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  public record Plan(String returnId, String outboundId, long orderId, long orderLineId, long warehouseId,
                     String trackingNumber, int expectedQuantity) {
  }

  public record Receipt(String returnId, String outboundId, long orderId, long orderLineId, long warehouseId,
                        String trackingNumber, int expectedQuantity, Integer receivedQuantity, String status,
                        Instant receivedAt, Instant inspectedAt, String supplementId, Integer qualifiedQuantity,
                        String supplementNote, Instant supplementAt) {
  }
}
