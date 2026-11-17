package vip.fairy.commerceflow.commerce.payment.infrastructure.adapter;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vip.fairy.commerceflow.commerce.payment.application.PaymentGatewayClient;
import vip.fairy.commerceflow.commerce.payment.application.RedisIdempotencyService;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentChannelV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseOutcomeV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseResultV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentContractSemantics;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCreateCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentSnapshotV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundSnapshotV1;
import vip.fairy.commerceflow.commerce.payment.application.port.OrderPaymentRecordPort;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import vip.fairy.commerceflow.commerce.refund.domain.RefundEventView;
import vip.fairy.commerceflow.commerce.refund.domain.RefundView;
import vip.fairy.commerceflow.commerce.refund.infrastructure.RefundMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.PaymentCancellationCrashProbe;

@Component
public class LocalPaymentOrchestrationAdapter implements PaymentOrchestrationV1 {

  private final OrderPaymentRecordPort payments;
  private final PaymentGatewayClient gateway;
  private final RedisIdempotencyService redisIdempotency;
  private final RefundMapper refunds;
  private final PaymentCancellationCrashProbe crashLab;

  public LocalPaymentOrchestrationAdapter(OrderPaymentRecordPort payments,
                                          PaymentGatewayClient gateway,
                                          RedisIdempotencyService redisIdempotency,
                                          RefundMapper refunds,
                                          PaymentCancellationCrashProbe crashLab) {
    this.payments = payments;
    this.gateway = gateway;
    this.redisIdempotency = redisIdempotency;
    this.refunds = refunds;
    this.crashLab = crashLab;
  }

  @Override
  @Transactional
  public PaymentSnapshotV1 create(PaymentCreateCommandV1 command) {
    command.validate();
    PaymentChannel channel = PaymentChannel.valueOf(command.channel().name());
    var merchant = payments.merchantAccount(channel)
      .orElseThrow(() -> new BusinessRuleException("Payment channel is disabled"));
    String fingerprint = PaymentContractSemantics.requestFingerprint(command);
    payments.lockIdempotencyKey(command.idempotencyKey());
    Optional<PaymentView> existing = payments.byKey(command.idempotencyKey());
    if (existing.isPresent()) {
      PaymentView payment = existing.get();
      if (!payment.getOrderId().equals(command.orderId())
        || !merchant.getId().equals(payment.getMerchantAccountId())
        || !fingerprint.equals(payment.getRequestFingerprint())) {
        throw new BusinessRuleException(
          "Idempotency key was already used with different payment parameters");
      }
      redisIdempotency.rememberPayment(command.idempotencyKey(), payment.getPaymentNumber());
      return snapshot(payment);
    }
    String paymentNumber = number("PAY");
    PaymentGatewayClient.Result remote = gateway.create(paymentNumber, channel,
      "Order " + command.orderNumber(), command.amount(), command.currency(), "NONE");
    payments.create(command.orderId(), merchant.getId(), paymentNumber, command.idempotencyKey(),
      channel, command.currency(), fingerprint, remote.channelTransactionNo(), command.amount(),
      "PENDING", "", null, remote.expiresInstant());
    PaymentView payment = payments.byKey(command.idempotencyKey()).orElseThrow();
    payments.checkout(payment.getId(), remote.checkoutUrl(), remote.actionType(), remote.qrPayload());
    payments.event(payment.getId(), UUID.randomUUID().toString(), "PAYMENT_PENDING", "CREATED",
      "PENDING", "Waiting for signed asynchronous notification");
    redisIdempotency.rememberPayment(command.idempotencyKey(), payment.getPaymentNumber());
    return snapshot(payments.byKey(command.idempotencyKey()).orElseThrow());
  }

  @Override
  public Optional<PaymentSnapshotV1> latest(PaymentQueryV1 query) {
    query.validate();
    return payments.latest(query.orderId()).map(this::snapshot);
  }

  @Override
  public Optional<RefundSnapshotV1> refund(PaymentQueryV1 query) {
    query.validate();
    return refunds.byOrder(query.orderId()).map(this::refundSnapshot);
  }

  @Override
  @Transactional
  public PaymentCloseResultV1 closePending(PaymentCloseCommandV1 command) {
    command.validate();
    var pending = payments.pending(command.orderId());
    for (PaymentView payment : pending) {
      gateway.close(payment);
    }
    crashLab.pause(command.orderId(), PaymentCancellationCrashProbe.Phase.AFTER_CHANNEL_CLOSE);
    if (payments.successfulCount(command.orderId()) > 0) {
      return closeResult(command, PaymentCloseOutcomeV1.PAYMENT_ALREADY_SUCCEEDED, 0,
        "A successful payment was observed while closing pending payments");
    }
    int closed = 0;
    for (PaymentView payment : pending) {
      if (payments.close(payment.getId(), command.reason())) {
        closed++;
        payments.event(payment.getId(), UUID.randomUUID().toString(), "PAYMENT_CLOSED_BY_ORDER",
          "PENDING", "CLOSED", command.reason());
      }
    }
    return closeResult(command, PaymentCloseOutcomeV1.CLOSED, closed,
      "Pending payments were closed");
  }

  private PaymentCloseResultV1 closeResult(PaymentCloseCommandV1 command,
                                           PaymentCloseOutcomeV1 outcome,
                                           int closed,
                                           String message) {
    PaymentCloseResultV1 result = new PaymentCloseResultV1(PaymentCloseResultV1.VERSION,
      UUID.randomUUID().toString(), outcome, Instant.now(), "commerce-service-payment",
      command.orderId(), closed, message, command.correlationId(), command.commandId(),
      command.traceparent());
    result.validate();
    return result;
  }

  private PaymentSnapshotV1 snapshot(PaymentView payment) {
    return new PaymentSnapshotV1(PaymentSnapshotV1.VERSION, payment.getOrderId(),
      payment.getPaymentNumber(), payment.getMerchantNo(),
      PaymentChannelV1.valueOf(payment.getChannel()), payment.getChannelTransactionNo(),
      payment.getStatus(), payment.getFailureReason(), payment.getAmount(),
      payment.getRefundedAmount(), payment.getCurrency(), payment.getCreatedAt(),
      payment.getPaidAt(), payment.getExpiresAt(), payment.getCheckoutUrl(),
      payment.getActionType(), payment.getQrPayload());
  }

  private RefundSnapshotV1 refundSnapshot(RefundView refund) {
    return new RefundSnapshotV1(RefundSnapshotV1.VERSION, refund.getRefundNumber(),
      refund.getOrderId(), refund.getStatus(), refund.getInventoryStatus(), refund.getAmount(),
      refund.getCurrency(), refund.getReasonCode(), refund.getReasonDetail(),
      refund.getChannelRefundNo(), refund.getFailureReason(), refund.getRequestedAt(),
      refund.getSucceededAt(), refund.getFailedAt(), refunds.events(refund.getId()).stream()
        .map(this::refundEvent)
        .toList());
  }

  private RefundSnapshotV1.Event refundEvent(RefundEventView event) {
    return new RefundSnapshotV1.Event(event.getId(), event.getEventType(), event.getFromStatus(),
      event.getToStatus(), event.getDetail(), event.getCreatedAt());
  }

  private String number(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "")
      .substring(0, 16).toUpperCase();
  }
}
