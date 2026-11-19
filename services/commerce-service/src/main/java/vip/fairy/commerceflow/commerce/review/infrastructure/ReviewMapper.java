package vip.fairy.commerceflow.commerce.review.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.commerce.review.domain.ProductReview;
import vip.fairy.commerceflow.commerce.review.domain.ProductReviewView;
import java.math.BigDecimal;
import java.util.List;

public interface ReviewMapper extends BaseMapper<ProductReview> {

  List<Long> ratedProductIds();

  ProductReviewView byItem(Long itemId);

  List<ProductReviewView> reviews(Long productId);

  BigDecimal average(Long productId);

  int count(Long productId);
}
