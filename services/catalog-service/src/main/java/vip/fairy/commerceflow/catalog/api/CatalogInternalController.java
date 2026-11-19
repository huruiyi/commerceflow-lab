package vip.fairy.commerceflow.catalog.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.catalog.application.CatalogApplicationService;

import java.util.List;

@RestController
@RequestMapping("/internal/v1")
public class CatalogInternalController {

  private static final String MERCHANT_HEADER = "X-Merchant-Id";

  private final CatalogApplicationService catalog;

  public CatalogInternalController(CatalogApplicationService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/products")
  List<CatalogProductV1> list(@RequestParam(required = false) String lang) {
    return catalog.list(lang);
  }

  @GetMapping("/products/{productId}")
  CatalogProductV1 detail(@PathVariable Long productId,
                          @RequestParam(required = false) String lang) {
    return catalog.detail(productId, lang);
  }

  @PostMapping("/products/snapshots")
  List<CatalogProductSnapshotV1> snapshots(
    @Valid @RequestBody CatalogSnapshotBatchRequest request) {
    return catalog.snapshots(request.productIds());
  }

  @GetMapping("/merchant/products")
  List<CatalogProductV1> merchantList(@RequestHeader(MERCHANT_HEADER) Long merchantId) {
    return catalog.merchantList(merchantId);
  }

  @PostMapping("/merchant/products")
  CatalogProductV1 create(@RequestHeader(MERCHANT_HEADER) Long merchantId,
                          @Valid @RequestBody CatalogCreateProductRequest request) {
    return catalog.create(merchantId, request);
  }

  @PutMapping("/merchant/products/{productId}/profile")
  CatalogProductV1 updateProfile(@RequestHeader(MERCHANT_HEADER) Long merchantId,
                                 @PathVariable Long productId,
                                 @Valid @RequestBody CatalogProfileUpdateRequest request,
                                 @RequestHeader(value = "X-Correlation-Id", required = false)
                                 String correlationId,
                                 @RequestHeader(value = "X-Causation-Id", required = false)
                                 String causationId) {
    return catalog.updateProfile(merchantId, productId, request, correlationId, causationId);
  }

  @PutMapping("/merchant/products/{productId}/price")
  CatalogProductV1 updatePrice(@RequestHeader(MERCHANT_HEADER) Long merchantId,
                               @PathVariable Long productId,
                               @Valid @RequestBody CatalogPriceUpdateRequest request,
                               @RequestHeader(value = "X-Correlation-Id", required = false)
                               String correlationId,
                               @RequestHeader(value = "X-Causation-Id", required = false)
                               String causationId) {
    return catalog.updatePrice(merchantId, productId, request, correlationId, causationId);
  }

  @PutMapping("/merchant/products/{productId}/status")
  CatalogProductV1 updateStatus(@RequestHeader(MERCHANT_HEADER) Long merchantId,
                                @PathVariable Long productId,
                                @Valid @RequestBody CatalogStatusUpdateRequest request,
                                @RequestHeader(value = "X-Correlation-Id", required = false)
                                String correlationId,
                                @RequestHeader(value = "X-Causation-Id", required = false)
                                String causationId) {
    return catalog.updateStatus(merchantId, productId, request, correlationId, causationId);
  }

  @PutMapping("/merchant/products/{productId}/translations/{locale}")
  CatalogProductV1 updateTranslation(@RequestHeader(MERCHANT_HEADER) Long merchantId,
                                     @PathVariable Long productId, @PathVariable String locale,
                                     @Valid @RequestBody CatalogTranslationUpdateRequest request,
                                     @RequestHeader(value = "X-Correlation-Id", required = false)
                                     String correlationId,
                                     @RequestHeader(value = "X-Causation-Id", required = false)
                                     String causationId) {
    return catalog.updateTranslation(merchantId, productId, locale, request,
      correlationId, causationId);
  }
}
