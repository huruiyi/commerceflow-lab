package vip.fairy.commerceflow.commerce.ordering.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import vip.fairy.commerceflow.commerce.ordering.domain.NewOrder;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderReviewableItem;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;

public interface OrderStorePort {

  String status(Long id);

  int markLogisticsDelivered(Long id, Instant deliveredAt);

  int insertOrder(NewOrder order, List<OrderItemSnapshot> items, OrderAddressSnapshot address,
                  Long actorId);

  void insertEventDetailed(Long orderId, Long actorId, String type, String fromStatus,
                           String toStatus, String detail, String triggerSource,
                           Long scheduledTaskId);

  default void insertEvent(Long orderId, Long actorId, String type, String fromStatus,
                           String toStatus, String detail) {
    insertEventDetailed(orderId, actorId, type, fromStatus, toStatus, detail, "USER", null);
  }

  List<ShopOrderView> list(Long customerId);

  Optional<ShopOrderView> find(Long customerId, Long id);

  Optional<ShopOrderView> lock(Long customerId, Long id);

  Optional<ShopOrderView> lockById(Long id);

  List<ShopOrderView> listMerchant(Long merchantId);

  Optional<ShopOrderView> findMerchant(Long merchantId, Long orderId);

  List<OrderItemView> items(Long orderId);

  OrderAddressView address(Long orderId);

  List<OrderEventView> events(Long orderId);

  Long orderCustomerId(Long orderId);

  Optional<OrderReviewableItem> reviewable(Long customerId, Long itemId);

  int updateStatus(Long id, String fromStatus, String toStatus);

  int updateInventoryStatus(Long id, String fromStatus, String toStatus);

  int startInventoryCommit(Long id);

  int startCancellation(Long id, String reason);

  int finishCancellation(Long id);

  int restorePaidAfterCancellationRace(Long id);

  int restorePaidAfterLateSuccess(Long id);

  int markReceived(Long id, Instant receivedAt);

  int markCompleted(Long id, Instant completedAt);

  int merchantConfirm(Long orderId, Long merchantId);

  int merchantShip(Long orderId, Long merchantId, String carrier, String tracking);

  int updateShipment(Long orderId, String carrier, String tracking);
}
