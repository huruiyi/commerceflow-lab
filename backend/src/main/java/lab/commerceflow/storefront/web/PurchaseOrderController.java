package lab.commerceflow.storefront.web;

import lab.commerceflow.storefront.domain.AppUser;
import lab.commerceflow.storefront.service.*;
import lab.commerceflow.storefront.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class PurchaseOrderController {
  private final CurrentUserService currentUsers; private final PurchaseOrderService orders; private final LogisticsService logistics;
  private final OrderCancellationService cancellations;
  public PurchaseOrderController(CurrentUserService currentUsers,PurchaseOrderService orders,
      OrderCancellationService cancellations,LogisticsService logistics){this.currentUsers=currentUsers;this.orders=orders;this.cancellations=cancellations;this.logistics=logistics;}
  @PostMapping("/checkout") ShopOrderResponse checkout(Authentication a){return orders.checkout(currentUsers.require(a));}
  @GetMapping List<ShopOrderResponse> list(Authentication a){return orders.list(currentUsers.require(a));}
  @GetMapping("/{id}") ShopOrderResponse get(@PathVariable Long id,Authentication a){return orders.require(currentUsers.require(a),id);}
  @GetMapping("/{id}/logistics") LogisticsResponse logistics(@PathVariable Long id,Authentication a){return logistics.require(currentUsers.require(a),id);}
  @PostMapping("/{id}/payments") ShopOrderResponse pay(@PathVariable Long id,@Valid @RequestBody PaymentRequest r,Authentication a){return orders.pay(currentUsers.require(a),id,r);}
  @PostMapping("/{id}/cancel") ShopOrderResponse cancel(@PathVariable Long id,
      @Valid @RequestBody(required=false) CancelOrderRequest request,Authentication a){
    AppUser user=currentUsers.require(a);
    cancellations.cancelByCustomer(user,id,request==null?null:request.reason());
    return orders.require(user,id);
  }
}
