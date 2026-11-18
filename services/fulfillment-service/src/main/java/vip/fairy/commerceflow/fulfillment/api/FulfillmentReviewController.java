package vip.fairy.commerceflow.fulfillment.api;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.fulfillment.application.FulfillmentReview;
import vip.fairy.commerceflow.fulfillment.application.FulfillmentReview.Review;
import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;
import vip.fairy.commerceflow.fulfillment.domain.RecoveryRecord;
import vip.fairy.commerceflow.fulfillment.infrastructure.InternalSignature;

@RestController
@RequestMapping("/internal/v1/fulfillment/admin/operations")
@ConditionalOnProperty(name = "commerceflow.fulfillment.recovery-enabled", havingValue = "true")
public class FulfillmentReviewController {

  private final FulfillmentReview reviews;
  private final ObjectMapper mapper;
  private final InternalSignature signature;

  public FulfillmentReviewController(FulfillmentReview reviews, ObjectMapper mapper,
    @Value("${commerceflow.fulfillment.admin-secret}") String secret,
    @Value("${commerceflow.fulfillment.secret}") String businessSecret) {
    if (secret.isBlank() || secret.equals(businessSecret)) {
      throw new IllegalArgumentException("Recovery requires a distinct administrator signing key");
    }
    this.reviews = reviews;
    this.mapper = mapper;
    this.signature = new InternalSignature(secret);
  }

  @GetMapping
  List<ExecutionTask> list(HttpServletRequest request) {
    signature.require(request, "");
    return reviews.list(50, null);
  }

  @PostMapping("/search")
  List<ExecutionTask> search(@RequestBody String body, HttpServletRequest request) {
    signature.require(request, body);
    Search filter = read(body, Search.class);
    return reviews.list(filter.limit(), filter.shipment());
  }

  @GetMapping("/shipments/{orderId}")
  Review shipment(@PathVariable long orderId, HttpServletRequest request) {
    signature.require(request, "");
    return reviews.shipment(orderId);
  }

  @GetMapping("/merchant-operations/{commandId}")
  Review merchantOperation(@PathVariable UUID commandId, HttpServletRequest request) {
    signature.require(request, "");
    return reviews.merchantOperation(commandId);
  }

  @GetMapping("/{commandId}")
  Review detail(@PathVariable UUID commandId, HttpServletRequest request) {
    signature.require(request, "");
    return reviews.detail(commandId);
  }

  @PostMapping("/{commandId}/retry")
  ResponseEntity<RecoveryRecord> retry(@PathVariable UUID commandId, @RequestBody String body,
                                        HttpServletRequest request) {
    signature.require(request, body);
    Recovery recovery = read(body, Recovery.class);
    return ResponseEntity.accepted().body(reviews.retry(commandId, recovery.recoveryId(),
      recovery.operatorId(), recovery.reason(), recovery.evidence(), recovery.orderId(), recovery.merchantOperation()));
  }

  private <T> T read(String body, Class<T> type) {
    try {
      T result = mapper.readValue(body, type);
      if (result == null) throw new IllegalArgumentException("Request body is required");
      return result;
    } catch (JacksonException | IllegalArgumentException failure) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recovery request", failure);
    }
  }

  record Recovery(UUID recoveryId, long operatorId, String reason, String evidence, Long orderId,
                  boolean merchantOperation) {
  }

  record Search(int limit, boolean shipment) {
  }
}
