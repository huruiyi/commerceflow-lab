package vip.fairy.commerceflow.commerce.ordering.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryOrderV1;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@Component
public class DeliveredInventoryOrderAdapter implements DeliveredInventoryOrderV1 {

  private final OrderStorePort orders;

  public DeliveredInventoryOrderAdapter(OrderStorePort orders) {
    this.orders = orders;
  }

  @Override
  public Snapshot requireDelivered(long orderId) {
    var order = orders.lockById(orderId).orElseThrow(() -> new BusinessRuleException("Order does not exist"));
    if (!List.of("RECEIVED", "COMPLETED").contains(order.getStatus())
        || order.getShippedAt() == null || order.getLogisticsDeliveredAt() == null
        || !List.of("RECONCILIATION_REQUIRED", "RECONCILED").contains(order.getInventoryStatus())) {
      throw new BusinessRuleException("Order must be delivered and eligible for inventory reconciliation");
    }
    Map<Long, Integer> quantities = orders.items(orderId).stream()
      .collect(Collectors.toMap(OrderItemView::getProductId, OrderItemView::getQuantity, Math::addExact));
    return new Snapshot(order.getCreatedAt(), quantities);
  }

  @Override
  public void markReconciled(long orderId) {
    if (orders.updateInventoryStatus(orderId, "RECONCILIATION_REQUIRED", "RECONCILED") != 1
        && orders.lockById(orderId).filter(order -> "RECONCILED".equals(order.getInventoryStatus())).isEmpty()) {
      throw new BusinessRuleException("Order inventory status changed during reconciliation");
    }
  }
}
