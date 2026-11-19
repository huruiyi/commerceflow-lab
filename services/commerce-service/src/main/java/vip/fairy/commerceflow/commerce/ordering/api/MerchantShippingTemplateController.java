package vip.fairy.commerceflow.commerce.ordering.api;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.ordering.application.ShippingTemplateService;
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
}
