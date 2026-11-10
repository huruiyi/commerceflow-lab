package com.tanerinal.education.web.dto;

import com.tanerinal.education.domain.Product;

import java.math.BigDecimal;
import java.util.List;

public record ProductResponse(Long id, String name, String brief, String description,
                              BigDecimal price, String imageUrl, String thumbnailUrl,
                              BigDecimal averageRating,int reviewCount,List<ProductReviewResponse> reviews) {
  public static ProductResponse from(Product product,BigDecimal averageRating,int reviewCount,List<ProductReviewResponse> reviews) {
    return new ProductResponse(product.getId(), product.getName(), product.getBrief(),
        product.getDescription(), product.getPrice(), product.getImageUrl(), product.getThumbnailUrl(),averageRating,reviewCount,reviews);
  }
}
