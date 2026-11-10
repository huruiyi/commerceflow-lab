package com.tanerinal.education.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tanerinal.education.domain.Product;
import com.tanerinal.education.mapper.ProductMapper;
import com.tanerinal.education.web.dto.ProductResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {
  private final ProductMapper products;

  public ProductController(ProductMapper products) {
    this.products = products;
  }

  @GetMapping
  List<ProductResponse> list() {
    return products.selectList(new LambdaQueryWrapper<Product>().orderByAsc(Product::getId))
        .stream().map(ProductResponse::from).toList();
  }
}
