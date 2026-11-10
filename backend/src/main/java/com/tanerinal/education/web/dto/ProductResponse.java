package com.tanerinal.education.web.dto;

import com.tanerinal.education.domain.Product;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, String brief, String description,
                              BigDecimal price, String imageUrl, String thumbnailUrl) {
  public static ProductResponse from(Product product) {
    return new ProductResponse(product.getId(), product.getName(), product.getBrief(),
        product.getDescription(), product.getPrice(), product.getImageUrl(), product.getThumbnailUrl());
  }
}
