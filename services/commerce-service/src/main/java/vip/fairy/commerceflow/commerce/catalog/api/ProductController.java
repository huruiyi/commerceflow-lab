package vip.fairy.commerceflow.commerce.catalog.api;

import vip.fairy.commerceflow.commerce.catalog.application.ProductCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.StockAvailabilityQuery;

@RestController
@RequestMapping("/api/products")
public class ProductController {

  private final ProductCatalogService catalog;
  private final StockAvailabilityQuery stocks;

  public ProductController(ProductCatalogService catalog, StockAvailabilityQuery stocks) {
    this.catalog = catalog;
    this.stocks = stocks;
  }

  @PostMapping("/availability")
  Map<Long, StockAvailabilityQuery.Stock> availability(@RequestBody List<Long> ids) {
    if (ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id < 1)) {
      throw new IllegalArgumentException("Invalid product IDs");
    }
    return stocks.availability(ids);
  }

  @GetMapping
  List<ProductResponse> list(@RequestParam(required = false) String lang,
                             @RequestHeader(value = "Accept-Language", required = false)
                             String acceptLanguage) {
    return catalog.list(lang != null ? lang : acceptLanguage);
  }

  @GetMapping("/{productId}")
  ProductResponse detail(@PathVariable Long productId,
                         @RequestParam(required = false) String lang,
                         @RequestHeader(value = "Accept-Language", required = false)
                         String acceptLanguage) {
    return catalog.detail(productId, lang != null ? lang : acceptLanguage);
  }

  @GetMapping("/search")
  ProductCatalogService.SearchPage search(@RequestParam(defaultValue = "") String q,
                                         @RequestParam(required = false) String lang,
                                         @RequestParam(defaultValue = "default") String sort,
                                         @RequestParam(required = false) Long merchantId,
                                         @RequestParam(required = false) BigDecimal minPrice,
                                         @RequestParam(required = false) BigDecimal maxPrice,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "12") int size) {
    return catalog.search(q, lang, sort, merchantId, minPrice, maxPrice, page, size);
  }
}
