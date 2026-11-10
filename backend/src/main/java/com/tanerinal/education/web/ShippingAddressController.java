package com.tanerinal.education.web;

import com.tanerinal.education.domain.AppUser;
import com.tanerinal.education.service.CurrentUserService;
import com.tanerinal.education.service.ShippingAddressService;
import com.tanerinal.education.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/addresses")
@PreAuthorize("hasRole('CUSTOMER')")
public class ShippingAddressController {
  private final CurrentUserService currentUsers; private final ShippingAddressService addresses;
  public ShippingAddressController(CurrentUserService currentUsers, ShippingAddressService addresses) { this.currentUsers=currentUsers; this.addresses=addresses; }
  @GetMapping List<ShippingAddressResponse> list(Authentication a) { return addresses.list(currentUsers.require(a)); }
  @PostMapping ShippingAddressResponse create(@Valid @RequestBody ShippingAddressRequest r, Authentication a) { return addresses.create(currentUsers.require(a), r); }
  @PutMapping("/{id}") ShippingAddressResponse update(@PathVariable Long id, @Valid @RequestBody ShippingAddressRequest r, Authentication a) { return addresses.update(currentUsers.require(a), id, r); }
  @PutMapping("/{id}/default") ShippingAddressResponse setDefault(@PathVariable Long id, Authentication a) { return addresses.setDefault(currentUsers.require(a), id); }
  @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable Long id, Authentication a) { addresses.delete(currentUsers.require(a), id); }
  @PutMapping("/{id}/select") ShippingAddressResponse select(@PathVariable Long id, Authentication a) { return addresses.selectForCheckout(currentUsers.require(a), id); }
  @GetMapping("/selected") Map<String,Long> selected(Authentication a) {
    Long id=addresses.selectedAddressId(currentUsers.require(a)); return id == null ? Collections.emptyMap() : Map.of("addressId", id);
  }
}
