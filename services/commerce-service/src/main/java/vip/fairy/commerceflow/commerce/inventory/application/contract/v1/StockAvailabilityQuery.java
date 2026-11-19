package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.util.Collection;
import java.util.Map;

public interface StockAvailabilityQuery {

  Map<Long, Stock> availability(Collection<Long> productIds);

  record Stock(long productId, boolean listed, int available) {
  }
}
