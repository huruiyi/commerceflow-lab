package vip.fairy.commerceflow.commerce.catalog.api.merchant;

import jakarta.validation.Valid;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogMonitorProfile;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.application.MerchantCatalogService;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/merchant/catalog/v1/products")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantCatalogController {

  private final CurrentActorIdResolver currentActors;
  private final MerchantCatalogService catalog;

  public MerchantCatalogController(CurrentActorIdResolver currentActors, MerchantCatalogService catalog) {
    this.currentActors = currentActors;
    this.catalog = catalog;
  }

  @GetMapping
  List<CatalogProductV1> list(Authentication authentication) {
    return catalog.list(currentActors.requireId(authentication));
  }

  @PostMapping
  CatalogProductV1 create(@Valid @RequestBody CatalogCreateProductRequest request, Authentication authentication) {
    return catalog.create(currentActors.requireId(authentication), request);
  }

  @PutMapping("/{productId}/profile")
  CatalogProductV1 updateProfile(@PathVariable Long productId,
                                 @Valid @RequestBody CatalogProfileUpdateRequest request,
                                 @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                 @RequestHeader(value = "X-Causation-Id", required = false) String causationId,
                                 Authentication authentication) {
    return catalog.updateProfile(currentActors.requireId(authentication), productId, request,
      correlationId, causationId);
  }

  @PutMapping("/{productId}/monitor-profile")
  CatalogProductV1 updateMonitorProfile(@PathVariable Long productId,
                                        @Valid @RequestBody CatalogMonitorProfile.Update request,
                                        Authentication authentication) {
    return catalog.updateMonitorProfile(currentActors.requireId(authentication), productId, request);
  }

  @PutMapping("/{productId}/price")
  CatalogProductV1 updatePrice(@PathVariable Long productId,
                               @Valid @RequestBody CatalogPriceUpdateRequest request,
                               @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                               @RequestHeader(value = "X-Causation-Id", required = false) String causationId,
                               Authentication authentication) {
    return catalog.updatePrice(currentActors.requireId(authentication), productId, request,
      correlationId, causationId);
  }

  @PutMapping("/{productId}/status")
  CatalogProductV1 updateStatus(@PathVariable Long productId,
                                @Valid @RequestBody CatalogStatusUpdateRequest request,
                                @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                @RequestHeader(value = "X-Causation-Id", required = false) String causationId,
                                Authentication authentication) {
    return catalog.updateStatus(currentActors.requireId(authentication), productId, request,
      correlationId, causationId);
  }

  @PutMapping("/{productId}/translations/{locale}")
  CatalogProductV1 updateTranslation(@PathVariable Long productId,
                                     @PathVariable String locale,
                                     @Valid @RequestBody CatalogTranslationUpdateRequest request,
                                     @RequestHeader(value = "X-Correlation-Id", required = false)
                                     String correlationId,
                                     @RequestHeader(value = "X-Causation-Id", required = false)
                                     String causationId,
                                     Authentication authentication) {
    return catalog.updateTranslation(currentActors.requireId(authentication), productId, locale, request,
      correlationId, causationId);
  }
}
