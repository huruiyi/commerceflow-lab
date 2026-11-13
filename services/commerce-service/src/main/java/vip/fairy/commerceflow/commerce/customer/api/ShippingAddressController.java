package vip.fairy.commerceflow.commerce.customer.api;

import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.customer.application.ShippingAddressService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/addresses")
@PreAuthorize("hasRole('CUSTOMER')")
public class ShippingAddressController {
  private final CurrentUserService currentUsers;
  private final ShippingAddressService addresses;

  public ShippingAddressController(CurrentUserService currentUsers, ShippingAddressService addresses) {
    this.currentUsers = currentUsers;
    this.addresses = addresses;
  }

  @GetMapping
  List<ShippingAddressResponse> list(Authentication authentication) {
    return addresses.list(currentUsers.require(authentication));
  }

  @PostMapping
  ShippingAddressResponse create(@Valid @RequestBody ShippingAddressRequest request,
                                 Authentication authentication) {
    return addresses.create(currentUsers.require(authentication), request);
  }

  @PutMapping("/{id}")
  ShippingAddressResponse update(@PathVariable Long id,
                                 @Valid @RequestBody ShippingAddressRequest request,
                                 Authentication authentication) {
    return addresses.update(currentUsers.require(authentication), id, request);
  }

  @PutMapping("/{id}/default")
  ShippingAddressResponse setDefault(@PathVariable Long id, Authentication authentication) {
    return addresses.setDefault(currentUsers.require(authentication), id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable Long id, Authentication authentication) {
    addresses.delete(currentUsers.require(authentication), id);
  }

  @PutMapping("/{id}/select")
  ShippingAddressResponse select(@PathVariable Long id, Authentication authentication) {
    return addresses.selectForCheckout(currentUsers.require(authentication), id);
  }

  @GetMapping("/selected")
  Map<String, Long> selected(Authentication authentication) {
    Long id = addresses.selectedAddressId(currentUsers.require(authentication));
    return id == null ? Map.of() : Map.of("addressId", id);
  }
}
