package vip.fairy.commerceflow.commerce.ordering.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.InvoiceOrderReadV1;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;

import java.util.Optional;

@Service
public class InvoiceOrderQuery implements InvoiceOrderReadV1 {

  private final OrderStorePort orders;

  public InvoiceOrderQuery(OrderStorePort orders) {
    this.orders = orders;
  }

  @Override
  public Optional<Order> find(long customerId, long orderId) {
    return orders.find(customerId, orderId).map(order -> {
      var lines = orders.items(orderId).stream()
        .map(line -> new Line(line.getMerchantId(), line.getSubtotal(), line.getDiscountAmount()))
        .toList();
      return new Order(order.getTotalAmount(), order.getProductSubtotal(), order.getDiscountAmount(),
        order.getShippingFee(), order.getCurrency(), lines);
    });
  }
}
