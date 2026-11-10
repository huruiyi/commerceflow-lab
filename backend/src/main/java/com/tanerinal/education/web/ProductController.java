package com.tanerinal.education.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tanerinal.education.domain.Product;
import com.tanerinal.education.mapper.ProductMapper;
import com.tanerinal.education.mapper.ReviewMapper;
import com.tanerinal.education.web.dto.ProductResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {
  private final ProductMapper products;
  private final ReviewMapper reviews;

  public ProductController(ProductMapper products,ReviewMapper reviews) {
    this.products = products;
    this.reviews = reviews;
  }

  @GetMapping
  List<ProductResponse> list() {
    return products.selectList(new LambdaQueryWrapper<Product>().orderByAsc(Product::getId))
        .stream().map(product->ProductResponse.from(product,reviews.average(product.getId()),reviews.count(product.getId()),reviews.reviews(product.getId()).stream().map(com.tanerinal.education.web.dto.ProductReviewResponse::from).toList())).toList();
  }
}
