package vip.fairy.commerceflow.commerce.merchant.application.contract.v1;

import java.util.Set;

public interface StoreTradeV1 {

  Set<Long> activeMerchantIds();

  void requireActive(Long merchantId, boolean lock);
}
