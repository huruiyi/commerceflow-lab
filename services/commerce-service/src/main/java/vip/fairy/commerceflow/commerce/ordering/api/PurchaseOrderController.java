package vip.fairy.commerceflow.commerce.ordering.api;

import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsService;
import vip.fairy.commerceflow.commerce.ordering.application.OrderCancellationService;
import vip.fairy.commerceflow.commerce.ordering.application.PurchaseOrderService;
import vip.fairy.commerceflow.commerce.payment.api.PaymentRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
  ShopOrderResponse checkout(Authentication authentication) {
    return orders.checkout(currentUsers.require(authentication));
  }

  @GetMapping
  List<ShopOrderResponse> list(Authentication authentication) {
    return orders.list(currentUsers.require(authentication));
  }

  @GetMapping("/{id}")
  ShopOrderResponse get(@PathVariable Long id, Authentication authentication) {
    return orders.require(currentUsers.require(authentication), id);
  }

  @GetMapping("/{id}/logistics")
  LogisticsResponse logistics(@PathVariable Long id, Authentication authentication) {
    return logistics.require(currentUsers.require(authentication), id);
  }

  @PostMapping("/{id}/payments")
  ShopOrderResponse pay(@PathVariable Long id,
                        @Valid @RequestBody PaymentRequest request,
                        Authentication authentication) {
    return orders.pay(currentUsers.require(authentication), id, request);
  }

  @PostMapping("/{id}/cancel")
  ShopOrderResponse cancel(@PathVariable Long id,
                           @Valid @RequestBody(required = false) CancelOrderRequest request,
                           Authentication authentication) {
    AppUser user = currentUsers.require(authentication);
    cancellations.cancelByCustomer(user, id, request == null ? null : request.reason());
    return orders.require(user, id);
  }
}
