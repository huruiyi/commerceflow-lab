package vip.fairy.commerceflow.commerce.refund.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import vip.fairy.commerceflow.commerce.refund.application.LineAfterSaleService;
import vip.fairy.commerceflow.commerce.refund.application.LineAfterSaleService.Offer;
import vip.fairy.commerceflow.commerce.refund.domain.LineAfterSale;
import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
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
  private final LineAfterSaleService afterSales;

  public RefundController(CurrentUserService users, OrderRefundPort orders,
                          PaymentOrchestrationV1 payments, PaymentWriteAdmissionV1 paymentWrites,
                          LineAfterSaleService afterSales) {
    this.users = users;
    this.orders = orders;
    this.payments = payments;
    this.paymentWrites = paymentWrites;
    this.afterSales = afterSales;
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

  @GetMapping("/api/purchase-orders/{orderId}/after-sales")
  @PreAuthorize("hasRole('CUSTOMER')")
  List<LineAfterSale> customerCases(@PathVariable Long orderId, Authentication authentication) {
    return afterSales.customerCases(users.require(authentication).getId(), orderId);
  }

  @GetMapping("/api/purchase-orders/after-sales/{id}/return-tracking")
  @PreAuthorize("hasRole('CUSTOMER')")
  LogisticsResponse customerReturnTracking(@PathVariable UUID id, Authentication authentication) {
    return afterSales.customerReturnTracking(users.require(authentication).getId(), id.toString());
  }

  @PostMapping("/api/purchase-orders/{orderId}/after-sales/quote")
  @PreAuthorize("hasRole('CUSTOMER')")
  Offer quote(@PathVariable Long orderId, @Valid @RequestBody Quote request, Authentication authentication) {
    return afterSales.quote(users.require(authentication).getId(), orderId, request.orderLineId(), request.quantity(),
      request.warehouseId(), request.outboundId());
  }

  @PostMapping("/api/purchase-orders/{orderId}/after-sales")
  @PreAuthorize("hasRole('CUSTOMER')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  LineAfterSale apply(@PathVariable Long orderId, @Valid @RequestBody Application request,
                     Authentication authentication) {
    return afterSales.submit(users.require(authentication).getId(), orderId, request.orderLineId(), request.quantity(),
      request.requestId(), request.reason(), request.warehouseId(), request.outboundId());
  }

  @PostMapping("/api/purchase-orders/after-sales/{id}/return")
  @PreAuthorize("hasRole('CUSTOMER')")
  LineAfterSale sendReturn(@PathVariable UUID id, @Valid @RequestBody ReturnParcel request,
                          Authentication authentication) {
    return afterSales.sendReturn(users.require(authentication).getId(), id.toString(),
      request.carrier(), request.trackingNumber(), request.returnShippingCost());
  }

  @GetMapping("/api/merchant/orders/{orderId}/after-sales")
  @PreAuthorize("hasRole('MERCHANT')")
  List<LineAfterSale> merchantCases(@PathVariable Long orderId, Authentication authentication) {
    return afterSales.merchantCases(users.require(authentication).getId(), orderId);
  }

  @GetMapping("/api/merchant/after-sales/{id}/return-tracking")
  @PreAuthorize("hasRole('MERCHANT')")
  LogisticsResponse merchantReturnTracking(@PathVariable UUID id, Authentication authentication) {
    return afterSales.merchantReturnTracking(users.require(authentication).getId(), id.toString());
  }

  @PostMapping("/api/merchant/after-sales/{id}/review")
  @PreAuthorize("hasRole('MERCHANT')")
  LineAfterSale review(@PathVariable UUID id, @Valid @RequestBody Decision request, Authentication authentication) {
    return afterSales.review(users.require(authentication).getId(), id.toString(), request.accepted(), request.note());
  }

  @PostMapping("/api/merchant/after-sales/{id}/inspection")
  @PreAuthorize("hasRole('MERCHANT')")
  LineAfterSale inspect(@PathVariable UUID id, @Valid @RequestBody Decision request, Authentication authentication) {
    return afterSales.inspect(users.require(authentication).getId(), id.toString(), request.accepted(), request.note());
  }

  record Quote(@NotNull @Positive Long orderLineId, @Positive int quantity, @Positive Long warehouseId,
               String outboundId) {
  }

  record Application(@NotNull @Positive Long orderLineId, @Positive int quantity, @NotNull UUID requestId,
                     @NotBlank @Size(max = 300) String reason, @Positive Long warehouseId, String outboundId) {
  }

  record ReturnParcel(@NotBlank @Size(max = 100) String carrier, @NotBlank @Size(max = 120) String trackingNumber,
                      @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal returnShippingCost) {
  }

  record Decision(@NotNull Boolean accepted, @NotBlank @Size(max = 300) String note) {
  }

  private String traceparent() {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }
}
