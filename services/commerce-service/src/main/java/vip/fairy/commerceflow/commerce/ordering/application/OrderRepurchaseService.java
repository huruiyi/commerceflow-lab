package vip.fairy.commerceflow.commerce.ordering.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.cart.application.contract.v1.RepurchaseCartV1;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class OrderRepurchaseService {

  private final OrderStorePort orders;
  private final RepurchaseCartV1 cart;

  public OrderRepurchaseService(OrderStorePort orders, RepurchaseCartV1 cart) {
    this.orders = orders;
    this.cart = cart;
  }

  public RepurchaseCartV1.Result addToCart(Long customerId, Long orderId, UUID requestId) {
    var order = orders.find(customerId, orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    if (!"RECEIVED".equals(order.getStatus()) && !"COMPLETED".equals(order.getStatus())) {
      throw new BusinessRuleException("Only received or completed orders can be purchased again");
    }
    var items = orders.items(orderId).stream()
      .map(item -> new RepurchaseCartV1.Item(item.getProductId(), item.getProductName(), item.getQuantity()))
      .toList();
    return cart.addOrderItems(customerId, orderId, requestId, items);
  }
}
