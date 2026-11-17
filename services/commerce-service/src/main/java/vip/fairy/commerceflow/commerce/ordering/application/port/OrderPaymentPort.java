package vip.fairy.commerceflow.commerce.ordering.application.port;

import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;

public interface OrderPaymentPort {

  ShopOrderView lockForPaymentResult(Long orderId);

  boolean paidFromPending(Long orderId);

  boolean paidAfterCancellationRace(Long orderId);

  boolean paidAfterLateSuccess(Long orderId);

  boolean startInventoryCommit(Long orderId);

  void resultApplied(Long orderId, Long customerId, String eventType,
                     String fromStatus, String detail);
}
