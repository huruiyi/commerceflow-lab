package vip.fairy.commerceflow.commerce.identity.infrastructure;

import java.util.List;

public interface PaymentReportGrantMapper {

  List<PlatformGrant> platform(long accountId);

  Long ownMerchant(long accountId);

  record PlatformGrant(boolean allMerchants, Long merchantId) {
  }
}
