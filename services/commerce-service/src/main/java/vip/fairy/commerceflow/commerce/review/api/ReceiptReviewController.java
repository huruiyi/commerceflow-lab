package vip.fairy.commerceflow.commerce.review.api;

import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.review.application.ReceiptReviewService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('CUSTOMER')")
public class ReceiptReviewController {

  private final CurrentUserService users;
  private final ReceiptReviewService service;

  public ReceiptReviewController(CurrentUserService users, ReceiptReviewService service) {
    this.users = users;
    this.service = service;
  }

  @PostMapping("/purchase-orders/{id}/receive")
  ShopOrderResponse receive(@PathVariable Long id, Authentication authentication) {
    return service.receive(users.require(authentication), id);
  }

  @PostMapping("/order-items/{id}/reviews")
  ProductReviewResponse review(@PathVariable Long id,
                               @Valid @RequestBody ReviewRequest request,
                               Authentication authentication) {
    return service.review(users.require(authentication), id, request);
  }
}
