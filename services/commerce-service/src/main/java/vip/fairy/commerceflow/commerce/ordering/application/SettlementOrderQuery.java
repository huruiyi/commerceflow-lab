package vip.fairy.commerceflow.commerce.ordering.application;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.SettlementOrderV1;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;

@Component
public class SettlementOrderQuery implements SettlementOrderV1 {

  private final OrderStorePort orders;

  public SettlementOrderQuery(OrderStorePort orders) {
    this.orders = orders;
  }

  @Override
  public List<Order> completed(Long merchant, Instant enabledAt, Instant cutoff) {
    return orders.listMerchant(merchant).stream()
      .filter(order -> !order.getCreatedAt().isBefore(enabledAt) && order.getCompletedAt() != null
        && !order.getCompletedAt().isAfter(cutoff))
      .map(order -> new Order(order.getId(), order.getCompletedAt(), order.getTotalAmount(),
        order.getShippingFee(), order.getCurrency())).toList();
  }

  @Override
  public List<Line> lines(Long orderId) {
    return orders.items(orderId).stream().map(line -> new Line(line.getId(), line.getMerchantId(),
      line.getSubtotal().subtract(line.getDiscountAmount()))).toList();
  }
}
