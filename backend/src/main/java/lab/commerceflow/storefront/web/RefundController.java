package lab.commerceflow.storefront.web;

import jakarta.validation.Valid;
import lab.commerceflow.storefront.service.*;
import lab.commerceflow.storefront.web.dto.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class RefundController {
  private final CurrentUserService users; private final RefundService refunds;
  public RefundController(CurrentUserService users,RefundService refunds){this.users=users;this.refunds=refunds;}
  @PostMapping("/api/purchase-orders/{orderId}/refunds") @PreAuthorize("hasRole('CUSTOMER')") @ResponseStatus(HttpStatus.ACCEPTED)
  RefundResponse request(@PathVariable Long orderId,@Valid @RequestBody RefundRequest request,Authentication authentication){return refunds.request(users.require(authentication),orderId,request);}
  @GetMapping("/api/purchase-orders/{orderId}/refunds") @PreAuthorize("hasRole('CUSTOMER')")
  List<RefundResponse> forOrder(@PathVariable Long orderId,Authentication authentication){return refunds.forOrder(users.require(authentication),orderId);}
  @GetMapping("/api/refunds/{refundNumber}") @PreAuthorize("hasRole('CUSTOMER')")
  RefundResponse get(@PathVariable String refundNumber,Authentication authentication){return refunds.get(users.require(authentication),refundNumber);}
  @GetMapping("/api/merchant/orders/{orderId}/refunds") @PreAuthorize("hasRole('MERCHANT')")
  RefundResponse merchant(@PathVariable Long orderId,Authentication authentication){return refunds.forMerchantOrder(users.require(authentication),orderId);}
}
