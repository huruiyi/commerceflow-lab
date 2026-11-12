package vip.fairy.commerceflow.commerce.fulfillment.api;

import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentService;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.fulfillment.api.FulfillmentRequest;
import vip.fairy.commerceflow.commerce.fulfillment.api.MerchantOrderResponse;
import jakarta.validation.Valid;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/merchant/orders")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantFulfillmentController {
  private final CurrentUserService users;
  private final MerchantFulfillmentService service;

  public MerchantFulfillmentController(CurrentUserService users, MerchantFulfillmentService service) {
    this.users = users;
    this.service = service;
  }

  @GetMapping
  List<MerchantOrderResponse> list(Authentication a) {
    return service.list(users.require(a));
  }

  @PostMapping("/{id}/confirm")
  MerchantOrderResponse confirm(@PathVariable Long id, Authentication a) {
    return service.confirm(users.require(a), id);
  }

  @PostMapping("/{id}/ship")
  MerchantOrderResponse ship(@PathVariable Long id, @Valid @RequestBody FulfillmentRequest request, Authentication a) {
    return service.ship(users.require(a), id, request);
  }

  @PutMapping("/{id}/shipment")
  MerchantOrderResponse updateShipment(@PathVariable Long id, @Valid @RequestBody FulfillmentRequest request, Authentication a) {
    return service.updateShipment(users.require(a), id, request);
  }
}
