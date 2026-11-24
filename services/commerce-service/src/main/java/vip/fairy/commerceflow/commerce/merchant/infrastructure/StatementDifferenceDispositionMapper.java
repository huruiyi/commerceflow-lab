package vip.fairy.commerceflow.commerce.merchant.infrastructure;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceDisposition;

public interface StatementDifferenceDispositionMapper {

  StatementDifferenceDisposition find(String reviewId);

  void insert(StatementDifferenceDisposition record);

  int close(@Param("reviewId") String reviewId, @Param("requestId") String requestId,
            @Param("actor") long actor, @Param("reason") String reason);
}
