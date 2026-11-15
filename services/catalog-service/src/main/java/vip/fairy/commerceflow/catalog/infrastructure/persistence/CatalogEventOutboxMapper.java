package vip.fairy.commerceflow.catalog.infrastructure.persistence;

import vip.fairy.commerceflow.catalog.application.CatalogProductEventV1;

public interface CatalogEventOutboxMapper {

  int insert(CatalogProductEventV1 event, String payload);
}
