package vip.fairy.commerceflow.commerce.payment.infrastructure.adapter;

import vip.fairy.commerceflow.commerce.payment.application.port.OrderPaymentRecordPort;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentMerchantAccount;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import vip.fairy.commerceflow.commerce.payment.infrastructure.OrderPaymentRecordMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class LocalOrderPaymentRecordAdapter implements OrderPaymentRecordPort {

  private final OrderPaymentRecordMapper payments;

  public LocalOrderPaymentRecordAdapter(OrderPaymentRecordMapper payments) {
    this.payments = payments;
  }

  @Override
  public Optional<PaymentMerchantAccount> merchantAccount(PaymentChannel channel) {
    return payments.merchantAccount(channel);
  }

  @Override
  public void lockIdempotencyKey(String key) {
    payments.ensureIdempotencyKey(key);
    payments.lockIdempotencyKey(key);
  }

  @Override
  public Optional<PaymentView> byKey(String key) {
    return payments.byKey(key);
  }

  @Override
  public Optional<PaymentView> latest(Long orderId) {
    return payments.latest(orderId);
  }

  @Override
  public List<PaymentView> pending(Long orderId) {
    return payments.pending(orderId);
  }

  @Override
  public int successfulCount(Long orderId) {
    return payments.successfulCount(orderId);
  }

  @Override
  public void create(Long orderId, Long merchantId, String number, String key,
                     PaymentChannel channel, String currency, String fingerprint,
                     String transactionNo, BigDecimal amount, String status, String reason,
                     Instant paidAt, Instant expiresAt) {
    payments.insert(orderId, merchantId, number, key, channel, currency, fingerprint,
      transactionNo, amount, status, reason, paidAt, expiresAt);
  }

  @Override
  public void event(Long paymentId, String eventId, String type, String fromStatus,
                    String toStatus, String detail) {
    payments.insertEvent(paymentId, eventId, type, fromStatus, toStatus, detail);
  }

  @Override
  public void checkout(Long paymentId, String checkoutUrl, String actionType, String qrPayload) {
    payments.updateCheckout(paymentId, checkoutUrl, actionType, qrPayload);
  }

  @Override
  public boolean close(Long paymentId, String reason) {
    return payments.close(paymentId, reason) == 1;
  }
}
