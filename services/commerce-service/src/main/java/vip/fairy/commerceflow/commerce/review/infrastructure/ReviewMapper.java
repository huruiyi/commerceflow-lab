package vip.fairy.commerceflow.commerce.review.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.review.domain.ProductReview;
import vip.fairy.commerceflow.commerce.review.domain.ProductReviewView;
import java.math.BigDecimal;
import java.util.List;

public interface ReviewMapper extends BaseMapper<ProductReview> {

  List<Long> ratedProductIds();

  ProductReviewView byItem(Long itemId);

  List<SummaryRow> summaries(@Param("productIds") List<Long> productIds);

  List<ProductReviewView> page(@Param("productId") Long productId, @Param("offset") long offset,
                               @Param("size") int size);

  record SummaryRow(Long productId, BigDecimal averageRating, int reviewCount) {
  }
}
