package vip.fairy.commerceflow.commerce.ordering.application.port;

import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;

import java.util.List;
import java.util.Optional;

public interface OrderRefundPort {

  Optional<ShopOrderView> find(Long customerId, Long orderId);

  Optional<ShopOrderView> lock(Long customerId, Long orderId);

  Optional<ShopOrderView> lock(Long orderId);

  List<OrderItemView> items(Long orderId);
  OrderStorePort.WarehousePlan warehousePlan(Long orderId);

  boolean start(Long orderId, String fromStatus);

  boolean complete(Long orderId);

  boolean restoreAfterFailure(Long orderId, String priorStatus);

  boolean moveInventory(Long orderId, String fromStatus, String toStatus);

  void resultRecorded(Long orderId, Long customerId, String eventType,
                      String fromStatus, String toStatus, String detail);
}
