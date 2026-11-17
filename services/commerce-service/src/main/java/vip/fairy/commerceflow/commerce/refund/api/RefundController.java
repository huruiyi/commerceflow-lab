package vip.fairy.commerceflow.commerce.refund.api;

import jakarta.validation.Valid;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderRefundPort;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundQueryV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundCreateCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentWriteAdmissionV1;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

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
  private final OrderRefundPort orders;
  private final PaymentOrchestrationV1 payments;
  private final PaymentWriteAdmissionV1 paymentWrites;

  public RefundController(CurrentUserService users, OrderRefundPort orders,
                          PaymentOrchestrationV1 payments, PaymentWriteAdmissionV1 paymentWrites) {
    this.users = users;
    this.orders = orders;
    this.payments = payments;
    this.paymentWrites = paymentWrites;
  }

  @PostMapping("/api/purchase-orders/{orderId}/refunds")
  @PreAuthorize("hasRole('CUSTOMER')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  RefundResponse request(
    @PathVariable Long orderId,
    @Valid @RequestBody RefundRequest request,
    Authentication authentication
  ) {
    paymentWrites.requireWritable();
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
    var user = users.require(authentication);
    orders.find(user.getId(), orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    return payments.refund(new PaymentQueryV1(PaymentQueryV1.VERSION, orderId))
      .map(value -> List.of(RefundResponse.from(value))).orElseGet(List::of);
  }

  @GetMapping("/api/refunds/{refundNumber}")
  @PreAuthorize("hasRole('CUSTOMER')")
  RefundResponse get(@PathVariable String refundNumber, Authentication authentication) {
    var user = users.require(authentication);
    var refund = payments.refund(new RefundQueryV1(RefundQueryV1.VERSION, refundNumber))
      .orElseThrow(() -> new ResourceNotFoundException("Refund not found"));
    orders.find(user.getId(), refund.orderId())
      .orElseThrow(() -> new ResourceNotFoundException("Refund not found"));
    return RefundResponse.from(refund);
  }

  @GetMapping("/api/merchant/orders/{orderId}/refunds")
  @PreAuthorize("hasRole('MERCHANT')")
  RefundResponse merchant(@PathVariable Long orderId, Authentication authentication) {
    var merchant = users.require(authentication);
    orders.items(orderId).stream().filter(item -> merchant.getId().equals(item.getMerchantId()))
      .findAny().orElseThrow(() -> new ResourceNotFoundException("Merchant order not found"));
    return payments.refund(new PaymentQueryV1(PaymentQueryV1.VERSION, orderId))
      .map(RefundResponse::from).orElse(null);
  }

  private String traceparent() {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }
}
