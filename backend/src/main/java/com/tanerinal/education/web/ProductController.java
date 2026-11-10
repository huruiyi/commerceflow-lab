package com.tanerinal.education.web;

import com.tanerinal.education.service.ProductCatalogService;
import com.tanerinal.education.web.dto.ProductResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
  List<ProductResponse> list() {
    return catalog.list();
  }
}
