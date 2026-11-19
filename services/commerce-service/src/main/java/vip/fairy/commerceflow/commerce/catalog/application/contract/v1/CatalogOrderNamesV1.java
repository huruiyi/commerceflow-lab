package vip.fairy.commerceflow.commerce.catalog.application.contract.v1;

import java.util.List;

public interface CatalogOrderNamesV1 {

  List<Long> matchingProductIds(String keyword, String locale);
}
