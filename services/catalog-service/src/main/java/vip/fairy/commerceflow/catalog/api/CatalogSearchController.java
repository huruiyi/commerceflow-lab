package vip.fairy.commerceflow.catalog.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.catalog.application.CatalogElasticsearchSearch.Comparison;
import vip.fairy.commerceflow.catalog.application.CatalogSearchService;

@RestController
@RequestMapping("/internal/v1/product-search")
public class CatalogSearchController {

  private final CatalogSearchService search;

  public CatalogSearchController(CatalogSearchService search) {
    this.search = search;
  }

  @PostMapping
  CatalogSearchService.Page search(@RequestBody CatalogSearchService.Request request) {
    return search.search(request);
  }

  @GetMapping("/index")
  CatalogSearchService.Status status() {
    return search.status();
  }

  @GetMapping("/index/comparison")
  Comparison comparison(@RequestParam(defaultValue = "0") long afterId,
                        @RequestParam(required = false) Long generation) {
    return search.compare(afterId, generation);
  }

  @PostMapping("/index/rebuild")
  CatalogSearchService.Status rebuild() {
    return search.rebuild();
  }
}
