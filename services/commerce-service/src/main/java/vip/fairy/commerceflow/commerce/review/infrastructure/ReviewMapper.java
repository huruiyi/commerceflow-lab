package vip.fairy.commerceflow.commerce.review.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.commerce.review.domain.ProductReview;
import vip.fairy.commerceflow.commerce.review.domain.ProductReviewView;
import vip.fairy.commerceflow.commerce.review.domain.ReviewableItem;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ReviewMapper extends BaseMapper<ProductReview> {

  Optional<ReviewableItem> reviewable(@Param("customerId") Long customerId, @Param("itemId") Long itemId);

  ProductReviewView byItem(Long itemId);

  List<ProductReviewView> reviews(Long productId);

  BigDecimal average(Long productId);

  int count(Long productId);
}
