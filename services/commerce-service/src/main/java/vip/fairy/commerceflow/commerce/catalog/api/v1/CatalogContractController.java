package vip.fairy.commerceflow.commerce.catalog.api.v1;

import jakarta.validation.Valid;
import vip.fairy.commerceflow.commerce.catalog.application.CatalogContractService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/catalog/v1/products")
public class CatalogContractController {

  private final CatalogContractService catalog;

  public CatalogContractController(CatalogContractService catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  List<CatalogProductV1> list(@RequestParam(required = false) String lang,
                              @RequestHeader(value = "Accept-Language", required = false)
                              String acceptLanguage) {
    return catalog.list(lang != null ? lang : acceptLanguage);
  }

  @GetMapping("/{productId}")
  CatalogProductV1 detail(@PathVariable Long productId,
                          @RequestParam(required = false) String lang,
                          @RequestHeader(value = "Accept-Language", required = false)
                          String acceptLanguage) {
    return catalog.detail(productId, lang != null ? lang : acceptLanguage);
  }

  @PostMapping("/snapshots")
  List<CatalogProductSnapshotV1> snapshots(
    @Valid @RequestBody CatalogSnapshotBatchRequest request
  ) {
    return catalog.snapshots(request.productIds());
  }
}
