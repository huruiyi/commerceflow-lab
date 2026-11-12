package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.cart.domain.NewShopOrder;
import vip.fairy.commerceflow.commerce.customer.domain.ShippingAddress;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentMerchantAccount;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.*;

public interface ShopOrderMapper {

  String status(Long id);


  int markLogisticsDelivered(@Param("id") Long id, @Param("deliveredAt") java.time.Instant deliveredAt);


  List<CheckoutCartItem> lockCartItems(Long userId);


  ShippingAddress selectedAddress(Long userId);


  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertOrder(NewShopOrder order);


  void insertItem(@Param("orderId") Long orderId, @Param("item") CheckoutCartItem item, @Param("subtotal") BigDecimal subtotal);


  void insertAddress(@Param("orderId") Long orderId, @Param("a") ShippingAddress address);

  default void insertEvent(Long orderId, Long actorId, String type, String fromStatus,
                           String toStatus, String detail) {
    insertEventDetailed(orderId, actorId, type, fromStatus, toStatus, detail, "USER", null);
  }


  void insertEventDetailed(@Param("orderId") Long orderId, @Param("actorId") Long actorId,
                           @Param("type") String type, @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus, @Param("detail") String detail,
                           @Param("triggerSource") String triggerSource,
                           @Param("scheduledTaskId") Long scheduledTaskId);


  List<ShopOrderView> list(Long customerId);


  Optional<ShopOrderView> find(@Param("customerId") Long customerId, @Param("id") Long id);


  Optional<ShopOrderView> lock(@Param("customerId") Long customerId, @Param("id") Long id);


  Optional<ShopOrderView> lockById(Long id);


  List<OrderItemView> items(Long orderId);


  OrderAddressView address(Long orderId);


  List<OrderEventView> events(Long orderId);


  Optional<PaymentMerchantAccount> merchantAccount(PaymentChannel channel);


  void ensurePaymentIdempotencyKey(String key);


  String lockPaymentIdempotencyKey(String key);


  Optional<PaymentView> paymentByKey(String key);


  Optional<PaymentView> latestPayment(Long orderId);


  List<PaymentView> pendingPayments(Long orderId);


  int successfulPaymentCount(Long orderId);


  void insertPayment(@Param("orderId") Long orderId, @Param("merchantId") Long merchantId,
                     @Param("number") String number, @Param("key") String key, @Param("channel") PaymentChannel channel,
                     @Param("currency") String currency, @Param("fingerprint") String fingerprint,
                     @Param("transactionNo") String transactionNo, @Param("amount") BigDecimal amount,
                     @Param("status") String status, @Param("reason") String reason,
                     @Param("paidAt") java.time.Instant paidAt, @Param("expiresAt") java.time.Instant expiresAt);


  void insertPaymentEvent(@Param("paymentId") Long paymentId, @Param("eventId") String eventId,
                          @Param("type") String type, @Param("fromStatus") String fromStatus,
                          @Param("toStatus") String toStatus, @Param("detail") String detail);


  void updateCheckout(@Param("id") Long id, @Param("checkoutUrl") String checkoutUrl, @Param("actionType") String actionType, @Param("qrPayload") String qrPayload);


  Optional<PaymentView> lockPaymentByTransaction(String transactionNo);


  void insertNotification(@Param("paymentId") Long paymentId, @Param("channel") String channel, @Param("notificationId") String notificationId, @Param("transactionNo") String transactionNo, @Param("processingStatus") String processingStatus, @Param("digest") String digest, @Param("reason") String reason, @Param("rawStatus") String rawStatus);


  int finishPayment(@Param("id") Long id, @Param("toStatus") String toStatus, @Param("reason") String reason);


  int closePendingPayment(@Param("id") Long id, @Param("reason") String reason);


  Long orderCustomerId(Long orderId);


  int updateStatus(@Param("id") Long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);


  int startCancellation(@Param("id") Long id, @Param("reason") String reason);


  int finishCancellation(Long id);


  int restorePaidAfterCancellationRace(Long id);


  int restorePaidAfterLateSuccess(Long id);


  int markReceived(@Param("id") Long id, @Param("receivedAt") java.time.Instant receivedAt);


  int markCompleted(@Param("id") Long id, @Param("completedAt") java.time.Instant completedAt);
}
