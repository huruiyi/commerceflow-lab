package vip.fairy.commerceflow.commerce.ordering.api;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.ordering.application.ShippingTemplateService;
import vip.fairy.commerceflow.commerce.ordering.application.ShippingTemplateService.Rate;
import vip.fairy.commerceflow.commerce.ordering.application.ShippingTemplateService.Template;

@RestController
@RequestMapping("/api/merchant/shipping-template")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantShippingTemplateController {

  private final ShippingTemplateService templates;
  private final CurrentUserService users;

  public MerchantShippingTemplateController(ShippingTemplateService templates, CurrentUserService users) {
    this.templates = templates;
    this.users = users;
  }

  @GetMapping
  Template get(Authentication authentication) {
    return templates.get(users.require(authentication).getId());
  }

  @PutMapping
  Template replace(Authentication authentication, @Valid @RequestBody Template template) {
    return templates.replace(users.require(authentication).getId(), template);
  }

  @PutMapping("/default-rate")
  Template updateDefault(Authentication authentication, @Valid @RequestBody DefaultRate request) {
    return templates.updateDefault(users.require(authentication).getId(), request.fee());
  }

  @PostMapping("/regions")
  Template addRegion(Authentication authentication, @Valid @RequestBody Rate request) {
    return templates.addRegion(users.require(authentication).getId(), request);
  }

  @PutMapping("/regions")
  Template updateRegion(Authentication authentication, @RequestParam String countryCode,
                        @RequestParam String stateProvince, @Valid @RequestBody Rate request) {
    return templates.updateRegion(users.require(authentication).getId(), countryCode, stateProvince, request);
  }

  @PatchMapping("/regions")
  Template setRegionEnabled(Authentication authentication, @RequestParam String countryCode,
                            @RequestParam String stateProvince, @Valid @RequestBody RegionStatus request) {
    return templates.setRegionEnabled(
      users.require(authentication).getId(), countryCode, stateProvince, request.enabled());
  }

  @DeleteMapping("/regions")
  Template deleteRegion(Authentication authentication, @RequestParam String countryCode,
                        @RequestParam String stateProvince) {
    return templates.deleteRegion(users.require(authentication).getId(), countryCode, stateProvince);
  }

  record DefaultRate(@NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal fee) {
  }

  record RegionStatus(@NotNull Boolean enabled) {
  }
}
