package com.tanerinal.education.service;

import com.tanerinal.education.domain.AppUser;
import com.tanerinal.education.domain.CustomerOrder;
import com.tanerinal.education.domain.Product;
import com.tanerinal.education.mapper.CustomerOrderMapper;
import com.tanerinal.education.mapper.ProductMapper;
import com.tanerinal.education.web.dto.OrderRequest;
import com.tanerinal.education.web.dto.OrderResponse;
import com.tanerinal.education.web.dto.QuantityUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class OrderService {
  private final ProductMapper products;
  private final CustomerOrderMapper orders;

  public OrderService(ProductMapper products, CustomerOrderMapper orders) {
    this.products = products;
    this.orders = orders;
  }

  @Transactional
  public OrderResponse create(AppUser user, OrderRequest request) {
    Product product = products.selectById(request.getProductId());
    if (product == null) {
      throw new ResourceNotFoundException("Product not found");
    }
    String note = request.getNote() == null ? "" : request.getNote().trim();
    int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
    CustomerOrder order = new CustomerOrder(user.getId(), product.getId(), Instant.now(), note, quantity);
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
