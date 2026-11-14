package vip.fairy.commerceflow.commerce.refund.application;

import vip.fairy.commerceflow.commerce.payment.application.PaymentEventPipeline;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import vip.fairy.commerceflow.commerce.refund.domain.RefundView;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.refund.infrastructure.RefundMapper;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.ShopOrderMapper;
import vip.fairy.commerceflow.commerce.refund.api.RefundRequest;
import vip.fairy.commerceflow.commerce.refund.api.RefundResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundService {

  private static final String AUTO_REASON = "Automatic refund for late payment after cancellation";

  private final ShopOrderMapper orders;
  private final RefundMapper refunds;
  private final RefundGatewayClient gateway;
  private final PaymentEventPipeline events;
  private final TransactionTemplate transactions;
  private final TransactionTemplate requiresNew;

  public RefundService(ShopOrderMapper orders, RefundMapper refunds, RefundGatewayClient gateway,
                       PaymentEventPipeline events, PlatformTransactionManager manager) {
    this.orders = orders;
    this.refunds = refunds;
    this.gateway = gateway;
    this.events = events;
    this.transactions = new TransactionTemplate(manager);
    this.requiresNew = new TransactionTemplate(manager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public RefundResponse request(AppUser user, Long orderId, RefundRequest request) {
    Prepared prepared = transactions.execute(status -> prepare(user, orderId, request));
    if (prepared == null) throw new IllegalStateException("Refund transaction did not return a result");
    if (!prepared.created()) return response(prepared.refund());
    dispatch(prepared);
    return get(user, prepared.refund().getRefundNumber());
  }

  public void queueAutomatic(Long orderId, String paymentNotificationId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Automatic refund must be queued in the payment transaction");
    }
    Prepared prepared = prepareAutomatic(orderId, paymentNotificationId);
    if (!prepared.created()) return;
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        try {
          dispatch(prepared);
        } catch (RuntimeException ignored) {
          // The PROCESSING refund remains durable and the existing reconciler retries it.
        }
      }
    });
  }

  public List<RefundResponse> forOrder(AppUser user, Long orderId) {
    orders.find(user.getId(), orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    return refunds.byCustomerOrder(user.getId(), orderId)
      .map(value -> List.of(response(value))).orElseGet(List::of);
  }

  public RefundResponse get(AppUser user, String refundNumber) {
    return response(refunds.byCustomerNumber(user.getId(), refundNumber)
      .orElseThrow(() -> new ResourceNotFoundException("Refund not found")));
  }

  public RefundResponse forMerchantOrder(AppUser merchant, Long orderId) {
    orders.items(orderId).stream()
      .filter(item -> merchant.getId().equals(item.getMerchantId())).findAny()
      .orElseThrow(() -> new ResourceNotFoundException("Merchant order not found"));
    return refunds.byOrder(orderId).map(this::response).orElse(null);
  }

  private Prepared prepare(AppUser user, Long orderId, RefundRequest request) {
    ShopOrderView order = orders.lock(user.getId(), orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    PaymentView payment = refunds.lockSuccessfulPayment(orderId)
      .orElseThrow(() -> new BusinessRuleException("Successful payment not found"));
    return prepare(order, payment, request, false);
  }

  private Prepared prepareAutomatic(Long orderId, String notificationId) {
    ShopOrderView order = orders.lockById(orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    PaymentView payment = refunds.lockSuccessfulPayment(orderId)
      .orElseThrow(() -> new BusinessRuleException("Successful payment not found"));
    RefundRequest request = new RefundRequest("late-payment:" + notificationId,
      "CUSTOMER_CANCELLED", AUTO_REASON);
    return prepare(order, payment, request, true);
  }

  private Prepared prepare(ShopOrderView order, PaymentView payment, RefundRequest request,
                           boolean automatic) {
    String fingerprint = fingerprint(order.getCustomerId(), order, payment, request);
    var existing = refunds.byPayment(payment.getId());
    if (existing.isPresent()) {
      RefundView value = existing.get();
      if (!value.getIdempotencyKey().equals(request.idempotencyKey())
        || !value.getRequestFingerprint().equals(fingerprint)) {
        throw new BusinessRuleException("This payment already has a different refund request");
      }
      return new Prepared(value, payment, false);
    }
    if (!List.of("PAID", "CONFIRMED").contains(order.getStatus())) {
      throw new BusinessRuleException("Only paid or confirmed orders can be refunded before shipment");
    }
    if (orders.updateStatus(order.getId(), order.getStatus(), "REFUNDING") != 1) {
      throw new BusinessRuleException("Order status changed while requesting refund");
    }
    String number = number();
    String detail = request.reasonDetail() == null ? "" : request.reasonDetail().trim();
    try {
      refunds.insert(number, order.getId(), payment.getId(), order.getCustomerId(),
        request.idempotencyKey(), fingerprint, payment.getAmount(), payment.getCurrency(),
        request.reasonCode(), detail, order.getStatus());
    } catch (DuplicateKeyException exception) {
      RefundView concurrent = refunds.byPayment(payment.getId()).orElseThrow(() -> exception);
      return new Prepared(concurrent, payment, false);
    }
    RefundView refund = refunds.byPayment(payment.getId()).orElseThrow();
    String eventType = automatic ? "AUTO_REFUND_REQUESTED" : "REFUND_REQUESTED";
    refunds.insertEvent(refund.getId(), UUID.randomUUID().toString(), eventType,
      "REQUESTED", "PROCESSING", request.reasonCode());
    orders.insertEvent(order.getId(), order.getCustomerId(),
      automatic ? "ORDER_AUTO_REFUND_REQUESTED" : "ORDER_REFUND_REQUESTED",
      order.getStatus(), "REFUNDING", automatic ? AUTO_REASON : request.reasonCode());
    events.append(refund.getId().toString(), eventType,
      "{\"refundNumber\":\"" + number + "\"}");
    return new Prepared(refund, payment, true);
  }

  private void dispatch(Prepared prepared) {
    try {
      RefundGatewayClient.Result remote = gateway.create(prepared.refund(), prepared.payment());
      requiresNew.executeWithoutResult(status ->
        refunds.attachChannel(prepared.refund().getId(), remote.channelRefundNo()));
    } catch (BusinessRuleException uncertain) {
      // The request may already be accepted. Reconciliation uses the durable merchant refund number.
    }
  }

  private RefundResponse response(RefundView value) {
    return RefundResponse.from(value, refunds.events(value.getId()));
  }

  private String number() {
    return "REF-" + UUID.randomUUID().toString().replace("-", "")
      .substring(0, 16).toUpperCase();
  }

  private String fingerprint(Long customerId, ShopOrderView order, PaymentView payment,
                             RefundRequest request) {
    try {
      String value = customerId + "|" + order.getId() + "|" + payment.getId() + "|"
        + payment.getAmount().stripTrailingZeros().toPlainString() + "|"
        + payment.getCurrency() + "|" + request.reasonCode() + "|"
        + (request.reasonDetail() == null ? "" : request.reasonDetail().trim());
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record Prepared(RefundView refund, PaymentView payment, boolean created) {
  }
}
