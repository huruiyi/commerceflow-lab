package vip.fairy.commerceflow.commerce.catalog.infrastructure;

import vip.fairy.commerceflow.commerce.catalog.application.CatalogProductEventV1;

public interface CatalogEventOutboxMapper {

  int insert(CatalogProductEventV1 event, String payload);
}
