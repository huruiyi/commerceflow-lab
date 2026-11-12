package vip.fairy.commerceflow.commerce.ordering.api;

import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsService;
import vip.fairy.commerceflow.commerce.ordering.application.OrderCancellationService;
import vip.fairy.commerceflow.commerce.ordering.application.PurchaseOrderService;
import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
import vip.fairy.commerceflow.commerce.ordering.api.CancelOrderRequest;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.payment.api.PaymentRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class PurchaseOrderController {
  private final CurrentUserService currentUsers;
  private final PurchaseOrderService orders;
  private final LogisticsService logistics;
  private final OrderCancellationService cancellations;

  public PurchaseOrderController(CurrentUserService currentUsers, PurchaseOrderService orders,
                                 OrderCancellationService cancellations, LogisticsService logistics) {
    this.currentUsers = currentUsers;
    this.orders = orders;
    this.cancellations = cancellations;
    this.logistics = logistics;
  }

  @PostMapping("/checkout")
  ShopOrderResponse checkout(Authentication a) {
    return orders.checkout(currentUsers.require(a));
  }

  @GetMapping
  List<ShopOrderResponse> list(Authentication a) {
    return orders.list(currentUsers.require(a));
  }

  @GetMapping("/{id}")
  ShopOrderResponse get(@PathVariable Long id, Authentication a) {
    return orders.require(currentUsers.require(a), id);
  }

  @GetMapping("/{id}/logistics")
  LogisticsResponse logistics(@PathVariable Long id, Authentication a) {
    return logistics.require(currentUsers.require(a), id);
  }

  @PostMapping("/{id}/payments")
  ShopOrderResponse pay(@PathVariable Long id, @Valid @RequestBody PaymentRequest r, Authentication a) {
    return orders.pay(currentUsers.require(a), id, r);
  }

  @PostMapping("/{id}/cancel")
  ShopOrderResponse cancel(@PathVariable Long id,
                           @Valid @RequestBody(required = false) CancelOrderRequest request, Authentication a) {
    AppUser user = currentUsers.require(a);
    cancellations.cancelByCustomer(user, id, request == null ? null : request.reason());
    return orders.require(user, id);
  }
}
