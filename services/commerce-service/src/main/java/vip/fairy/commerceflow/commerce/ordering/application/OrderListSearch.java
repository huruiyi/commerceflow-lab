package vip.fairy.commerceflow.commerce.ordering.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.catalog.application.contract.v1.CatalogOrderNamesV1;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListPage;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListQuery;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListReadV1;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;

@Service
public class OrderListSearch implements OrderListReadV1 {

  private final OrderStorePort orders;
  private final CatalogOrderNamesV1 catalog;

  public OrderListSearch(OrderStorePort orders, CatalogOrderNamesV1 catalog) {
    this.orders = orders;
    this.catalog = catalog;
  }

  @Override
  public OrderListPage<ShopOrderView> search(Long customerId, Long merchantId, OrderListQuery query, String locale) {
    if (!query.keyword().isEmpty()) {
      try {
        query = query.withProductIds(catalog.matchingProductIds(query.keyword(), locale));
      } catch (ExternalServiceUnavailableException ignored) {
        // Stored order numbers and product names remain searchable during a catalog outage.
      }
    }
    return orders.search(customerId, merchantId, query);
  }
}
