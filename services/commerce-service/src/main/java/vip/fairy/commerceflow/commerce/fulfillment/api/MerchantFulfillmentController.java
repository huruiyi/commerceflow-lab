package vip.fairy.commerceflow.commerce.fulfillment.api;

import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentService;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
  List<MerchantOrderResponse> list(Authentication authentication) {
    return service.list(users.require(authentication));
  }

  @PostMapping("/{id}/confirm")
  MerchantOrderResponse confirm(@PathVariable Long id, Authentication authentication) {
    return service.confirm(users.require(authentication), id);
  }

  @PostMapping("/{id}/ship")
  MerchantOrderResponse ship(@PathVariable Long id,
                             @Valid @RequestBody FulfillmentRequest request,
                             Authentication authentication) {
    return service.ship(users.require(authentication), id, request);
  }

  @PutMapping("/{id}/shipment")
  MerchantOrderResponse updateShipment(@PathVariable Long id,
                                       @Valid @RequestBody FulfillmentRequest request,
                                       Authentication authentication) {
    return service.updateShipment(users.require(authentication), id, request);
  }
}
