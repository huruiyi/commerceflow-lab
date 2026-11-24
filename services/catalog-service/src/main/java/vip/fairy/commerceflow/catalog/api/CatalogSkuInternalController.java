package vip.fairy.commerceflow.catalog.api;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.catalog.application.CatalogSkuService;

@RestController
@RequestMapping("/internal/v1")
public class CatalogSkuInternalController {

  private final CatalogSkuService skus;

  CatalogSkuInternalController(CatalogSkuService skus) {
    this.skus = skus;
  }

  @GetMapping("/products/{productId}/skus")
  List<CatalogSkuV1> sellable(@PathVariable long productId) {
    return skus.sellable(productId);
  }

  @GetMapping("/merchant/products/{productId}/skus")
  List<CatalogSkuV1> list(@RequestHeader("X-Merchant-Id") long merchant, @PathVariable long productId) {
    return skus.list(merchant, productId);
  }

  @PostMapping("/merchant/products/{productId}/skus")
  CatalogSkuV1 create(@RequestHeader("X-Merchant-Id") long merchant, @PathVariable long productId,
                      @RequestBody @Valid CatalogSkuV1.Create request) {
    return skus.create(merchant, productId, request);
  }

  @PutMapping("/merchant/products/{productId}/skus/{skuId}")
  CatalogSkuV1 update(@RequestHeader("X-Merchant-Id") long merchant, @PathVariable long productId,
                      @PathVariable long skuId, @RequestBody @Valid CatalogSkuV1.Update request) {
    return skus.update(merchant, productId, skuId, request);
  }
}
