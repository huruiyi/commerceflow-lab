package vip.fairy.commerceflow.commerce.payment.infrastructure;

import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentMerchantAccount;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderPaymentRecordMapper {

  Optional<PaymentMerchantAccount> merchantAccount(PaymentChannel channel);

  void ensureIdempotencyKey(String key);

  String lockIdempotencyKey(String key);

  Optional<PaymentView> byKey(String key);

  Optional<PaymentView> latest(Long orderId);

  List<PaymentView> pending(Long orderId);

  int successfulCount(Long orderId);

  void insert(@Param("orderId") Long orderId, @Param("merchantId") Long merchantId,
              @Param("number") String number, @Param("key") String key,
              @Param("channel") PaymentChannel channel, @Param("currency") String currency,
              @Param("fingerprint") String fingerprint,
              @Param("transactionNo") String transactionNo, @Param("amount") BigDecimal amount,
              @Param("status") String status, @Param("reason") String reason,
              @Param("paidAt") Instant paidAt, @Param("expiresAt") Instant expiresAt);

  void insertEvent(@Param("paymentId") Long paymentId, @Param("eventId") String eventId,
                   @Param("type") String type, @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus, @Param("detail") String detail);

  void updateCheckout(@Param("id") Long id, @Param("checkoutUrl") String checkoutUrl,
                      @Param("actionType") String actionType,
                      @Param("qrPayload") String qrPayload);

  int close(@Param("id") Long id, @Param("reason") String reason);
}
