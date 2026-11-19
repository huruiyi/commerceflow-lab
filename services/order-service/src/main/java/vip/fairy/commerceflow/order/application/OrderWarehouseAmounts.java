package vip.fairy.commerceflow.order.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.ItemView;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderLineRefundMapper;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderStoreMapper;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderWarehouseAmountMapper;

@Service
public class OrderWarehouseAmounts {

  private final OrderStoreMapper orders;
  private final OrderLineRefundMapper locks;
  private final OrderWarehouseAmountMapper amounts;

  public OrderWarehouseAmounts(OrderStoreMapper orders, OrderLineRefundMapper locks,
                               OrderWarehouseAmountMapper amounts) {
    this.orders = orders;
    this.locks = locks;
    this.amounts = amounts;
  }

  @Transactional
  public boolean freeze(Snapshot command) {
    if (command == null || command.orderId() <= 0 || command.reservations() == null
        || command.reservations().isEmpty()) throw new IllegalArgumentException("Order and allocations required");
    var order = locks.lockOrder(command.orderId());
    if (order == null || !"PENDING_PAYMENT".equals(order.status()) || !"RESERVED".equals(order.inventoryStatus())) {
      return false;
    }
    List<Amount> expected = calculate(command, orders.items(command.orderId()),
      orders.findById(command.orderId()).shippingFee());
    if (expected.isEmpty()) return false;
    var existing = amounts.orderAmounts(command.orderId());
    if (!existing.isEmpty()) return existing.equals(expected);
    expected.forEach(amounts::insert);
    return true;
  }

  @Transactional(readOnly = true)
  public Plan plan(long orderId) {
    if (orderId <= 0) throw new IllegalArgumentException("Order is required");
    return new Plan(amounts.orderAmounts(orderId), amounts.shippedLines(orderId));
  }

  private List<Amount> calculate(Snapshot command, List<ItemView> items, BigDecimal freight) {
    Map<Long, List<Reservation>> byProduct = new HashMap<>();
    var seen = new HashSet<ProductWarehouse>();
    for (Reservation reservation : command.reservations()) {
      if (reservation == null || reservation.productId() <= 0 || reservation.warehouseId() <= 0
          || reservation.quantity() <= 0
          || !seen.add(new ProductWarehouse(reservation.productId(), reservation.warehouseId()))) {
        return List.of();
      }
      byProduct.computeIfAbsent(reservation.productId(), ignored -> new ArrayList<>()).add(reservation);
    }
    int ordered = items.stream().mapToInt(ItemView::quantity).sum();
    int totalAllocated = command.reservations().stream().mapToInt(Reservation::quantity).sum();
    if (ordered == 0 || ordered != totalAllocated) return List.of();
    List<Amount> result = new ArrayList<>();
    BigDecimal remainingFreight = freight;
    int counted = 0;
    for (var item : items.stream().sorted(Comparator.comparingLong(ItemView::id)).toList()) {
      var matches = byProduct.remove(item.productId());
      if (matches == null || matches.stream().mapToInt(Reservation::quantity).sum() != item.quantity()) {
        return List.of();
      }
      matches.sort(Comparator.comparingLong(Reservation::warehouseId));
      int allocated = 0;
      BigDecimal remainingDiscount = item.discountAmount();
      for (Reservation match : matches) {
        int quantity = match.quantity();
        allocated += quantity;
        counted += quantity;
        BigDecimal discount = allocated == item.quantity() ? remainingDiscount
          : proportion(item.discountAmount(), quantity, item.quantity());
        BigDecimal freightShare = counted == ordered ? remainingFreight : proportion(freight, quantity, ordered);
        result.add(new Amount(command.orderId(), item.id(), item.productId(), match.warehouseId(), quantity,
          item.unitPrice().multiply(BigDecimal.valueOf(quantity)), discount, freightShare));
        remainingFreight = remainingFreight.subtract(freightShare);
        remainingDiscount = remainingDiscount.subtract(discount);
      }
    }
    return result;
  }

  private BigDecimal proportion(BigDecimal amount, int quantity, int total) {
    return amount.multiply(BigDecimal.valueOf(quantity)).divide(BigDecimal.valueOf(total), 2, RoundingMode.DOWN);
  }

  public record Snapshot(long orderId, List<Reservation> reservations) {
  }

  public record Reservation(long productId, long warehouseId, int quantity) {
  }

  private record ProductWarehouse(long productId, long warehouseId) {
  }

  /** 已锁定的分仓数量和金额，不随后续发货或退款顺序改变。 */
  public record Amount(long orderId, long orderLineId, long productId, long warehouseId, int quantity,
                       BigDecimal grossAmount, BigDecimal discountAmount, BigDecimal freightAmount) {
  }

  public record Shipment(String packageId, long warehouseId, long orderLineId, int quantity) {
  }

  public record Plan(List<Amount> amounts, List<Shipment> shipments) {
  }
}
