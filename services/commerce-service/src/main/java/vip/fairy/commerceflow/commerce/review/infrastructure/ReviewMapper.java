package vip.fairy.commerceflow.commerce.review.infrastructure;

import vip.fairy.commerceflow.commerce.review.domain.ProductReviewView;
import vip.fairy.commerceflow.commerce.review.domain.ReviewableItem;

import java.math.BigDecimal;
import java.util.*;

import org.apache.ibatis.annotations.*;

public interface ReviewMapper {

  Optional<ReviewableItem> reviewable(@Param("customerId") Long customerId, @Param("itemId") Long itemId);


  void insert(@Param("itemId") Long itemId, @Param("productId") Long productId, @Param("customerId") Long customerId, @Param("rating") int rating, @Param("comment") String comment);


  ProductReviewView byItem(Long itemId);


  List<ProductReviewView> reviews(Long productId);


  BigDecimal average(Long productId);


  int count(Long productId);
}
