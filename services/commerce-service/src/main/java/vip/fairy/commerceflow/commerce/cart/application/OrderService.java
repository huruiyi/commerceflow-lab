package vip.fairy.commerceflow.commerce.cart.application;

import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.cart.domain.CustomerOrder;
import vip.fairy.commerceflow.commerce.catalog.application.CatalogProductLookup;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CustomerOrderMapper;
import vip.fairy.commerceflow.commerce.cart.api.OrderRequest;
import vip.fairy.commerceflow.commerce.cart.api.OrderResponse;
import vip.fairy.commerceflow.commerce.cart.api.QuantityUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class OrderService {

  private final CatalogProductLookup products;
  private final CustomerOrderMapper orders;

  public OrderService(CatalogProductLookup products, CustomerOrderMapper orders) {
    this.products = products;
    this.orders = orders;
  }

  @Transactional
  public OrderResponse create(AppUser user, OrderRequest request) {
    CatalogProductLookup.ProductSnapshot product = products.require(request.getProductId());
    String note = request.getNote() == null ? "" : request.getNote().trim();
    int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
    CustomerOrder order = new CustomerOrder(user.getId(), product.id(), Instant.now(), note, quantity);
    orders.insert(order);
    return OrderResponse.from(order, product);
  }

  @Transactional
  public OrderResponse updateQuantity(AppUser user, Long orderId, QuantityUpdateRequest request) {
    CustomerOrder order = orders.selectById(orderId);
    if (order == null || !order.getUserId().equals(user.getId())) {
      throw new ResourceNotFoundException("Order not found");
    }
    order.changeQuantity(request.getQuantity());
    orders.updateById(order);
    return orders.selectViewForUser(orderId, user.getId())
      .map(OrderResponse::from)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
  }

  @Transactional(readOnly = true)
  public List<OrderResponse> list(AppUser user) {
    return orders.selectAllForUser(user.getId()).stream().map(OrderResponse::from).toList();
  }
}
