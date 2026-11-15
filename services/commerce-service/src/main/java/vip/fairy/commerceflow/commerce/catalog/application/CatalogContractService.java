package vip.fairy.commerceflow.commerce.catalog.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductSnapshotV1;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.client.CatalogServiceClient;

import java.util.List;

@Service
public class CatalogContractService {

  private final CatalogServiceClient catalog;

  public CatalogContractService(CatalogServiceClient catalog) {
    this.catalog = catalog;
  }

  public List<CatalogProductV1> list(String locale) {
    return catalog.list(locale);
  }

  public CatalogProductV1 detail(Long productId, String locale) {
    return catalog.detail(productId, locale);
  }

  public List<CatalogProductSnapshotV1> snapshots(List<Long> productIds) {
    return catalog.snapshots(productIds);
  }
}
