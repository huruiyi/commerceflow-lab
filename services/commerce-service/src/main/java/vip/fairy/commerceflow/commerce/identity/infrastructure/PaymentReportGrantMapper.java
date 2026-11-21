package vip.fairy.commerceflow.commerce.identity.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.identity.application.contract.v1.PaymentReportAccessV1.MerchantOption;

public interface PaymentReportGrantMapper {

  List<PlatformGrant> platform(long accountId);

  Long ownMerchant(long accountId);

  List<MerchantOption> merchantOptions(@Param("merchantIds") List<Long> merchantIds);

  record PlatformGrant(boolean allMerchants, Long merchantId) {
  }
}
