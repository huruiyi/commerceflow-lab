package vip.fairy.commerceflow.order.application;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderLineRefundMapper;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderPackageMapper;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderWarehouseAmountMapper;

@Service
public class OrderLineRefundService {

  private final OrderLineRefundMapper store;
  private final OrderPackageMapper packages;
  private final OrderWarehouseAmountMapper amounts;

  public OrderLineRefundService(OrderLineRefundMapper store, OrderPackageMapper packages,
                                OrderWarehouseAmountMapper amounts) {
    this.store = store;
    this.packages = packages;
    this.amounts = amounts;
  }

  @Transactional
  public boolean apply(Command command) {
    if (command == null || command.action() == null || !Set.of("HOLD", "SETTLE", "RELEASE").contains(command.action())
        || command.caseId() == null || !command.caseId().matches("[0-9a-f-]{36}") || command.quantity() <= 0
        || command.orderId() == null || command.orderLineId() == null || command.customerId() == null
        || command.merchantId() == null || command.warehouse() <= 0) {
      throw new IllegalArgumentException("Invalid line refund admission");
    }
    var order = store.lockOrder(command.orderId());
    var line = store.item(command.orderId(), command.orderLineId());
    if (order == null || line == null || !command.customerId().equals(order.customerId())
        || !command.merchantId().equals(line.merchantId())) return false;
    var prior = store.find(command.caseId());
    if (prior == null) {
      var planned = amounts.orderAmounts(command.orderId());
      if (command.warehouseId() == null && planned.stream().anyMatch(amount -> amount.warehouseId() != 1)) {
        return false;
      }
      int capacity = planned.isEmpty() && command.warehouse() == 1 ? line.quantity() : planned.stream()
        .filter(amount -> amount.orderLineId() == command.orderLineId() && amount.warehouseId() == command.warehouse())
        .mapToInt(OrderWarehouseAmounts.Amount::quantity).sum();
      if (!"HOLD".equals(command.action()) || !Set.of("PAID", "CONFIRMED").contains(order.status())
          || !"COMMITTED".equals(order.inventoryStatus()) || order.shipmentIntentKey() != null
          || store.heldCount(command.orderId()) != 0
          || command.quantity() > capacity
            - store.settledAtWarehouse(command.orderId(), command.orderLineId(), command.warehouse())
            - packages.allocatedAtWarehouse(command.orderLineId(), command.warehouse())) {
        return false;
      }
      store.hold(command);
      return true;
    }
    if (!command.orderId().equals(prior.orderId()) || !command.orderLineId().equals(prior.orderLineId())
        || prior.quantity() != command.quantity() || prior.warehouseId() != command.warehouse()) return false;
    if ("HOLD".equals(command.action())) return !"RELEASED".equals(prior.status());
    String target = "SETTLE".equals(command.action()) ? "SETTLED" : "RELEASED";
    if (target.equals(prior.status())) return true;
    if (store.finish(command.caseId(), target) != 1) return false;
    if ("SETTLED".equals(target)) {
      store.closeEmptyOrder(command.orderId());
      packages.finishIfFullyAllocated(command.orderId());
    }
    return true;
  }

  public record Command(String action, String caseId, Long orderId, Long orderLineId, Long customerId,
                        Long merchantId, int quantity, Long warehouseId) {

    public long warehouse() {
      return warehouseId == null ? 1 : warehouseId;
    }
  }
}
