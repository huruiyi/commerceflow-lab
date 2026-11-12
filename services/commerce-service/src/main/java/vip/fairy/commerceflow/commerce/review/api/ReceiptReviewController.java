package vip.fairy.commerceflow.commerce.review.api;

import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.review.application.ReceiptReviewService;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.review.api.ProductReviewResponse;
import vip.fairy.commerceflow.commerce.review.api.ReviewRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
  ShopOrderResponse receive(@PathVariable Long id, Authentication a) {
    return service.receive(users.require(a), id);
  }

  @PostMapping("/order-items/{id}/reviews")
  ProductReviewResponse review(@PathVariable Long id, @Valid @RequestBody ReviewRequest request, Authentication a) {
    return service.review(users.require(a), id, request);
  }
}
