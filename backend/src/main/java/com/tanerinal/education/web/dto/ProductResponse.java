package com.tanerinal.education.web.dto;

import com.tanerinal.education.domain.Product;

import java.math.BigDecimal;
import java.util.List;

public record ProductResponse(Long id, String name, String brief, String description,
                              BigDecimal price, String imageUrl, String thumbnailUrl,
                              BigDecimal averageRating,int reviewCount,List<ProductReviewResponse> reviews) {
  public static ProductResponse from(Product product,com.tanerinal.education.domain.ProductTranslation translation,BigDecimal averageRating,int reviewCount,List<ProductReviewResponse> reviews) {
    return new ProductResponse(product.getId(), translation==null?product.getName():translation.getName(), translation==null?product.getBrief():translation.getBrief(),
        translation==null?product.getDescription():translation.getDescription(), product.getPrice(), product.getImageUrl(), product.getThumbnailUrl(),averageRating,reviewCount,reviews);
  }
}
