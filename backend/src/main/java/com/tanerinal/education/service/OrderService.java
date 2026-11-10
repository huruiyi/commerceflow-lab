package com.tanerinal.education.service;

import com.tanerinal.education.domain.AppUser;
import com.tanerinal.education.domain.CustomerOrder;
import com.tanerinal.education.domain.Product;
import com.tanerinal.education.repository.CustomerOrderRepository;
import com.tanerinal.education.repository.ProductRepository;
import com.tanerinal.education.web.dto.OrderRequest;
import com.tanerinal.education.web.dto.OrderResponse;
import com.tanerinal.education.web.dto.QuantityUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;

@Service
public class OrderService {
  private final ProductRepository products;
  private final CustomerOrderRepository orders;

  public OrderService(ProductRepository products, CustomerOrderRepository orders) {
    this.products = products;
    this.orders = orders;
  }

  @Transactional
  public OrderResponse create(AppUser user, OrderRequest request) {
    Product product = products.findById(request.getProductId())
        .orElseThrow(() -> new EntityNotFoundException("Product not found"));
    String note = request.getNote() == null ? "" : request.getNote().trim();
    int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
    return OrderResponse.from(orders.save(new CustomerOrder(user, product, Instant.now(), note, quantity)));
  }

  @Transactional
  public OrderResponse updateQuantity(AppUser user, Long orderId, QuantityUpdateRequest request) {
    CustomerOrder order = orders.findForUser(orderId, user.getId())
        .orElseThrow(() -> new EntityNotFoundException("Order not found"));
    order.changeQuantity(request.getQuantity());
    return OrderResponse.from(order);
  }

  @Transactional(readOnly = true)
  public List<OrderResponse> list(AppUser user) {
    return orders.findAllForUser(user.getId()).stream().map(OrderResponse::from).toList();
  }
}
