package com.tanerinal.education.web;

import com.tanerinal.education.domain.AppUser;
import com.tanerinal.education.service.CurrentUserService;
import com.tanerinal.education.service.OrderService;
import com.tanerinal.education.web.dto.OrderRequest;
import com.tanerinal.education.web.dto.OrderResponse;
import com.tanerinal.education.web.dto.QuantityUpdateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
  private final CurrentUserService currentUsers;
  private final OrderService orders;

  public OrderController(CurrentUserService currentUsers, OrderService orders) {
    this.currentUsers = currentUsers;
    this.orders = orders;
  }

  @GetMapping
  List<OrderResponse> list(Authentication authentication) {
    return orders.list(currentUsers.require(authentication));
  }

  @PostMapping
  OrderResponse create(@Valid @RequestBody OrderRequest request, Authentication authentication) {
    AppUser user = currentUsers.require(authentication);
    return orders.create(user, request);
  }

  @PutMapping("/{orderId}/quantity")
  OrderResponse updateQuantity(@PathVariable Long orderId,
                               @Valid @RequestBody QuantityUpdateRequest request,
                               Authentication authentication) {
    AppUser user = currentUsers.require(authentication);
    return orders.updateQuantity(user, orderId, request);
  }
}
