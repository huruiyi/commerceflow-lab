package vip.fairy.commerceflow.commerce.identity.application.contract.v1;

import java.util.List;

public interface PaymentReportAccessV1 {

  /**
   * Returns approved merchant IDs, or null for full platform report access.
   */
  List<Long> platform(long accountId, Long requestedMerchant);

  List<Long> merchant(long accountId, Long requestedMerchant);

  List<MerchantOption> merchantOptions(long accountId, boolean platform);

  record MerchantOption(long merchantId, String name) {
  }
}
