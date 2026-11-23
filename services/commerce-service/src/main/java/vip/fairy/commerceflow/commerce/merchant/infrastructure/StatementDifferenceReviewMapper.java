package vip.fairy.commerceflow.commerce.merchant.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceReview;

public interface StatementDifferenceReviewMapper {

  StatementDifferenceReview find(String requestId);
  void insert(StatementDifferenceReview review);
  long count(@Param("merchant") long merchant, @Param("statement") String statement);
  List<StatementDifferenceReview> page(@Param("merchant") long merchant, @Param("statement") String statement,
                                       @Param("size") int size, @Param("offset") long offset);
}
