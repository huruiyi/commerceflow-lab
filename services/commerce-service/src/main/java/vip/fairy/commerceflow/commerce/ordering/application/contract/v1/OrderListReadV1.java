package vip.fairy.commerceflow.commerce.ordering.application.contract.v1;

import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;

public interface OrderListReadV1 {

  OrderListPage<ShopOrderView> search(Long customerId, Long merchantId, OrderListQuery query, String locale);
}
