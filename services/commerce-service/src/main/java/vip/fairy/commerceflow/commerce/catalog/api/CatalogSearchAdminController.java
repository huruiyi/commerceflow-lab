package vip.fairy.commerceflow.commerce.catalog.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.catalog.application.ProductCatalogService;
import vip.fairy.commerceflow.commerce.catalog.application.ProductCatalogService.SearchComparison;
import vip.fairy.commerceflow.commerce.catalog.application.ProductCatalogService.SearchStatus;

@RestController
@RequestMapping("/api/admin/catalog-search")
@PreAuthorize("hasRole('ADMIN')")
public class CatalogSearchAdminController {

  private final ProductCatalogService catalog;

  public CatalogSearchAdminController(ProductCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  SearchStatus status() {
    return catalog.searchStatus(false);
  }

  @GetMapping("/comparison")
  SearchComparison comparison(@RequestParam(defaultValue = "0") long afterId,
                              @RequestParam(required = false) Long generation) {
    return catalog.compareSearchIndex(afterId, generation);
  }

  @PostMapping("/rebuild")
  SearchStatus rebuild() {
    return catalog.searchStatus(true);
  }
}
