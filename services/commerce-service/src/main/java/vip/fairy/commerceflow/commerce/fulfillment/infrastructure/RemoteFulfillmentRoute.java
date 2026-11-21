package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.fulfillment.application.FulfillmentRecoveryRoute;
import vip.fairy.commerceflow.commerce.fulfillment.application.FulfillmentRecoveryRoute.MerchantReview;
import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentRoute;
import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentService.CommandResult;
import vip.fairy.commerceflow.commerce.fulfillment.application.FulfillmentRecoveryRoute.ShipmentReview;
import vip.fairy.commerceflow.commerce.fulfillment.application.contract.FulfillmentCommandV1;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentRecovery;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentRecovery;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Component
public class RemoteFulfillmentRoute implements MerchantFulfillmentRoute, FulfillmentRecoveryRoute {

  private static final String ROOT = "/internal/v1/fulfillment";
  private static final String ADMIN = ROOT + "/admin/operations";

  private final RestClient client;
  private final ObjectMapper mapper;
  private final SecretKeySpec businessKey;
  private final SecretKeySpec adminKey;

  public RemoteFulfillmentRoute(RestClient.Builder builder, ObjectMapper mapper,
    @Value("${commerceflow.fulfillment.remote.base-url}") String baseUrl,
    @Value("${commerceflow.fulfillment.remote.secret}") String businessSecret,
    @Value("${commerceflow.fulfillment.remote.admin-secret}") String adminSecret) {
    if (businessSecret.isBlank() || adminSecret.isBlank() || businessSecret.equals(adminSecret)) {
      throw new IllegalArgumentException("Remote fulfillment requires distinct business and administrator keys");
    }
    this.businessKey = new SecretKeySpec(businessSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    this.adminKey = new SecretKeySpec(adminSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    this.mapper = mapper;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2000);
    factory.setReadTimeout(4000);
    this.client = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
  }

  @Override
  public CommandResult submit(FulfillmentCommandV1 command) {
    return request(ROOT + "/commands", command, businessKey, CommandResult.class);
  }

  @Override
  public CommandResult commandStatus(Long merchantId, String commandId) {
    return request(ROOT + "/queries", new Query(id(commandId), merchantId, true), businessKey, CommandResult.class);
  }

  @Override
  public ShipmentJob shipment(Long merchantId, Long orderId) {
    return request(ROOT + "/shipment-queries", new ShipmentQuery(orderId, merchantId), businessKey,
      Task.class).shipment();
  }

  @Override
  public List<ShipmentJob> shipments(int limit) {
    return Arrays.stream(request(ADMIN + "/search", new Search(limit, true), adminKey, Task[].class))
      .map(Task::shipment).toList();
  }

  @Override
  public ShipmentReview shipment(Long orderId) {
    RemoteReview result = request(ADMIN + "/shipments/" + orderId, null, adminKey, RemoteReview.class);
    return new ShipmentReview(result.operation().shipment(),
      result.history().stream().map(entry -> entry.shipment(orderId)).toList());
  }

  @Override
  public ShipmentRecovery retryShipment(Long orderId, String commandId, Retry retry) {
    return retry(commandId, retry, orderId, false).shipment(orderId);
  }

  @Override
  public List<MerchantFulfillmentJob> merchantOperations(int limit) {
    return Arrays.stream(request(ADMIN + "/search", new Search(limit, false), adminKey, Task[].class))
      .map(Task::merchant).toList();
  }

  @Override
  public MerchantReview merchantOperation(String commandId) {
    RemoteReview result = request(ADMIN + "/merchant-operations/" + id(commandId), null, adminKey, RemoteReview.class);
    return new MerchantReview(
      result.operation().merchant(),
      result.history().stream().map(Recovery::merchant).toList());
  }

  @Override
  public MerchantFulfillmentRecovery retryMerchantOperation(String commandId, Retry retry) {
    return retry(commandId, retry, null, true).merchant();
  }

  private Recovery retry(String commandId, Retry retry, Long orderId, boolean merchantOperation) {
    return request(ADMIN + "/" + id(commandId) + "/retry",
      new ScopedRetry(retry.recoveryId(), retry.operatorId(), retry.reason(), retry.evidence(), orderId,
        merchantOperation), adminKey, Recovery.class);
  }

  private <T> T request(String path, Object value, SecretKeySpec key, Class<T> type) {
    HttpMethod method = value == null ? HttpMethod.GET : HttpMethod.POST;
    String payload = value == null ? "" : mapper.writeValueAsString(value);
    try {
      var request = client.method(method).uri(path).headers(headers -> sign(headers, method, path, payload, key));
      if (value != null) request.contentType(MediaType.APPLICATION_JSON).body(payload.getBytes(StandardCharsets.UTF_8));
      T reply = request.retrieve().body(type);
      if (reply == null) throw unavailable(null);
      return reply;
    } catch (RestClientResponseException failure) {
      throw switch (failure.getStatusCode().value()) {
        case 404 -> new ResourceNotFoundException("Fulfillment operation not found");
        case 409 -> new BusinessRuleException("Fulfillment request conflicts with the current operation");
        default -> unavailable(failure);
      };
    } catch (RestClientException failure) {
      throw unavailable(failure);
    }
  }

  private ExternalServiceUnavailableException unavailable(Throwable failure) {
    return new ExternalServiceUnavailableException("FULFILLMENT_SERVICE_UNAVAILABLE",
      "Fulfillment service is temporarily unavailable", failure);
  }

  private UUID id(String commandId) {
    try {
      return UUID.fromString(commandId);
    } catch (IllegalArgumentException failure) {
      throw new ResourceNotFoundException("Fulfillment operation not found");
    }
  }

  private void sign(HttpHeaders headers, HttpMethod method, String path, String payload, SecretKeySpec key) {
    try {
      String timestamp = Long.toString(Instant.now().getEpochSecond());
      String requestId = UUID.randomUUID().toString();
      HexFormat hex = HexFormat.of();
      byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
      String hash = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(payloadBytes));
      String canonical = String.join("\n", method.name(), path, timestamp, requestId, hash);
      Mac signer = Mac.getInstance("HmacSHA256");
      signer.init(key);
      headers.set("X-CommerceFlow-Timestamp", timestamp);
      headers.set("X-CommerceFlow-Request-Id", requestId);
      headers.set("X-CommerceFlow-Content-SHA256", hash);
      byte[] signature = signer.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
      headers.set("X-CommerceFlow-Signature", hex.formatHex(signature));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Cannot sign fulfillment request", failure);
    }
  }

  private record Query(UUID commandId, long merchantId, boolean executionOnly) {
  }

  private record ShipmentQuery(long orderId, long merchantId) {
  }

  private record Search(int limit, boolean shipment) {
  }

  private record ScopedRetry(String recoveryId, Long operatorId, String reason, String evidence, Long orderId,
                             boolean merchantOperation) {
  }

  private record RemoteReview(Task operation, List<Recovery> history) {
  }

  private record Task(String commandId, Long orderId, Long merchantId, String operation, String orderNumber,
                      String carrier, String trackingNumber, Long expectedVersion, String detail,
                      String step, ShipmentJob.Status status, String errorCode) {

    ShipmentJob shipment() {
      return new ShipmentJob(orderId, commandId, merchantId, orderNumber, carrier, trackingNumber,
        ShipmentJob.Step.valueOf(step), status, errorCode);
    }

    MerchantFulfillmentJob merchant() {
      return new MerchantFulfillmentJob(commandId, orderId, merchantId,
        MerchantFulfillmentJob.Operation.valueOf(operation), expectedVersion == null ? 0 : expectedVersion,
        carrier, trackingNumber, detail, MerchantFulfillmentJob.Step.valueOf(step), status, errorCode);
    }
  }

  private record Recovery(String commandId, String recoveryId, Long operatorId, String step, String previousError,
                          String reason, String evidence, Instant createdAt) {

    ShipmentRecovery shipment(Long orderId) {
      return new ShipmentRecovery(orderId, recoveryId, commandId, operatorId, ShipmentJob.Step.valueOf(step),
        previousError, reason, evidence, createdAt);
    }

    MerchantFulfillmentRecovery merchant() {
      return new MerchantFulfillmentRecovery(commandId, recoveryId, operatorId,
        MerchantFulfillmentJob.Step.valueOf(step), previousError, reason, evidence, createdAt);
    }
  }
}
