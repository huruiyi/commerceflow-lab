package vip.fairy.commerceflow.commerce.merchant.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.merchant.domain.MerchantPayout;

public interface MerchantPayoutMapper {

  MerchantPayout find(String id);

  MerchantPayout lock(String id);

  List<MerchantPayout> attempts(String statement);

  List<MerchantPayout> due();

  void insert(MerchantPayout payout);

  void observe(@Param("id") String id, @Param("status") String status, @Param("channel") String channel,
               @Param("resolvedAt") Instant resolvedAt);

  void defer(String id);
}
