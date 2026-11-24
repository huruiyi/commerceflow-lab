package vip.fairy.commerceflow.commerce.catalog.api.merchant;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogSkuV1;
import vip.fairy.commerceflow.commerce.catalog.application.MerchantCatalogService;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
@PreAuthorize("hasRole('MERCHANT')")
@RequestMapping("/api/merchant/catalog/v1/products/{productId}/skus")
public class MerchantSkuController {

  private final MerchantCatalogService catalog;
  private final CurrentActorIdResolver actors;

  MerchantSkuController(MerchantCatalogService catalog, CurrentActorIdResolver actors) {
    this.catalog = catalog;
    this.actors = actors;
  }

  @GetMapping
  List<CatalogSkuV1> list(Authentication actor, @PathVariable long productId) {
    return catalog.listSkus(actors.requireId(actor), productId);
  }

  @PostMapping
  CatalogSkuV1 create(Authentication actor, @PathVariable long productId,
                      @RequestBody @Valid CatalogSkuV1.Create request) {
    return catalog.createSku(actors.requireId(actor), productId, request);
  }

  @PutMapping("/{skuId}")
  CatalogSkuV1 update(Authentication actor, @PathVariable long productId, @PathVariable long skuId,
                      @RequestBody @Valid CatalogSkuV1.Update request) {
    return catalog.updateSku(actors.requireId(actor), productId, skuId, request);
  }
}
