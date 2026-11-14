package vip.fairy.commerceflow.commerce.catalog.api;

import vip.fairy.commerceflow.commerce.catalog.application.ProductCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

  private final ProductCatalogService catalog;

  public ProductController(ProductCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  List<ProductResponse> list(@RequestParam(required = false) String lang,
                             @RequestHeader(value = "Accept-Language", required = false)
                             String acceptLanguage) {
    return catalog.list(lang != null ? lang : acceptLanguage);
  }
}
