package vip.fairy.commerceflow.commerce.refund.api;

import jakarta.validation.Valid;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.refund.application.RefundService;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundCreateCommandV1;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundController {

  private final CurrentUserService users;
  private final RefundService refunds;
  private final PaymentOrchestrationV1 payments;

  public RefundController(CurrentUserService users, RefundService refunds,
                          PaymentOrchestrationV1 payments) {
    this.users = users;
    this.refunds = refunds;
    this.payments = payments;
  }

  @PostMapping("/api/purchase-orders/{orderId}/refunds")
  @PreAuthorize("hasRole('CUSTOMER')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  RefundResponse request(
    @PathVariable Long orderId,
    @Valid @RequestBody RefundRequest request,
    Authentication authentication
  ) {
    var user = users.require(authentication);
    String commandId = UUID.randomUUID().toString();
    String correlationId = UUID.randomUUID().toString();
    RefundCreateCommandV1 command = new RefundCreateCommandV1(RefundCreateCommandV1.VERSION,
      commandId, orderId, user.getId(), request.idempotencyKey(), request.reasonCode(),
      request.reasonDetail(), correlationId, correlationId, traceparent());
    return RefundResponse.from(payments.requestRefund(command));
  }

  @GetMapping("/api/purchase-orders/{orderId}/refunds")
  @PreAuthorize("hasRole('CUSTOMER')")
  List<RefundResponse> forOrder(@PathVariable Long orderId, Authentication authentication) {
    return refunds.forOrder(users.require(authentication), orderId);
  }

  @GetMapping("/api/refunds/{refundNumber}")
  @PreAuthorize("hasRole('CUSTOMER')")
  RefundResponse get(@PathVariable String refundNumber, Authentication authentication) {
    return refunds.get(users.require(authentication), refundNumber);
  }

  @GetMapping("/api/merchant/orders/{orderId}/refunds")
  @PreAuthorize("hasRole('MERCHANT')")
  RefundResponse merchant(@PathVariable Long orderId, Authentication authentication) {
    return refunds.forMerchantOrder(users.require(authentication), orderId);
  }

  private String traceparent() {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }
}
