package com.tanerinal.education.web;

import com.tanerinal.education.domain.AppUser;
import com.tanerinal.education.service.*;
import com.tanerinal.education.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class PurchaseOrderController {
  private final CurrentUserService currentUsers; private final PurchaseOrderService orders;
  public PurchaseOrderController(CurrentUserService currentUsers,PurchaseOrderService orders){this.currentUsers=currentUsers;this.orders=orders;}
  @PostMapping("/checkout") ShopOrderResponse checkout(Authentication a){return orders.checkout(currentUsers.require(a));}
  @GetMapping List<ShopOrderResponse> list(Authentication a){return orders.list(currentUsers.require(a));}
  @GetMapping("/{id}") ShopOrderResponse get(@PathVariable Long id,Authentication a){return orders.require(currentUsers.require(a),id);}
  @PostMapping("/{id}/payments") ShopOrderResponse pay(@PathVariable Long id,@Valid @RequestBody PaymentRequest r,Authentication a){return orders.pay(currentUsers.require(a),id,r);}
}
