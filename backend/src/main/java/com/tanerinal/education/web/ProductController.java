package com.tanerinal.education.web;

import com.tanerinal.education.repository.ProductRepository;
import com.tanerinal.education.web.dto.ProductResponse;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {
  private final ProductRepository products;

  public ProductController(ProductRepository products) {
    this.products = products;
  }

  @GetMapping
  List<ProductResponse> list() {
    return products.findAll(Sort.by("id")).stream().map(ProductResponse::from).toList();
  }
}
