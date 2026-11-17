package vip.fairy.commerceflow.commerce.payment.application.port;

import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentMerchantAccount;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderPaymentRecordPort {

  Optional<PaymentMerchantAccount> merchantAccount(PaymentChannel channel);

  void lockIdempotencyKey(String key);

  Optional<PaymentView> byKey(String key);

  Optional<PaymentView> latest(Long orderId);

  List<PaymentView> pending(Long orderId);

  int successfulCount(Long orderId);

  void create(Long orderId, Long merchantId, String number, String key, PaymentChannel channel,
              String currency, String fingerprint, String transactionNo, BigDecimal amount,
              String status, String reason, Instant paidAt, Instant expiresAt);

  void event(Long paymentId, String eventId, String type, String fromStatus,
             String toStatus, String detail);

  void checkout(Long paymentId, String checkoutUrl, String actionType, String qrPayload);

  boolean close(Long paymentId, String reason);
}
