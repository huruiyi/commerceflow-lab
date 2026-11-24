package vip.fairy.commerceflow.commerce.catalog.application;

import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogCreateProductRequest;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.StoreTradeV1;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogPriceUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogProfileUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogStatusUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogTranslationUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogSkuV1;
import vip.fairy.commerceflow.commerce.catalog.domain.ProductLifecycleStatus;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.client.CatalogServiceClient;

import java.util.List;

@Service
public class MerchantCatalogService {

  private final CatalogServiceClient catalog;
  private final StoreTradeV1 stores;

  public MerchantCatalogService(CatalogServiceClient catalog, StoreTradeV1 stores) {
    this.catalog = catalog;
    this.stores = stores;
  }

  public CatalogProductV1 create(Long merchantId, CatalogCreateProductRequest request) {
    stores.requireActive(merchantId, false);
    return catalog.create(merchantId, request);
  }

  public List<CatalogProductV1> list(Long merchantId) {
    return catalog.merchantList(merchantId);
  }

  public List<CatalogSkuV1> listSkus(long merchant, long productId) {
    return catalog.listSkus(merchant, productId);
  }

  public CatalogSkuV1 createSku(long merchant, long productId, CatalogSkuV1.Create request) {
    stores.requireActive(merchant, false);
    return catalog.createSku(merchant, productId, request);
  }

  public CatalogSkuV1 updateSku(long merchant, long productId, long skuId, CatalogSkuV1.Update request) {
    if (request.status() == ProductLifecycleStatus.ACTIVE) stores.requireActive(merchant, false);
    return catalog.updateSku(merchant, productId, skuId, request);
  }

  public CatalogProductV1 updateProfile(Long merchantId, Long productId,
                                        CatalogProfileUpdateRequest request,
                                        String correlationId, String causationId) {
    return catalog.updateProfile(merchantId, productId, request, correlationId, causationId);
  }

  public CatalogProductV1 updatePrice(Long merchantId, Long productId,
                                      CatalogPriceUpdateRequest request,
                                      String correlationId, String causationId) {
    return catalog.updatePrice(merchantId, productId, request, correlationId, causationId);
  }

  public CatalogProductV1 updateStatus(Long merchantId, Long productId,
                                       CatalogStatusUpdateRequest request,
                                       String correlationId, String causationId) {
    if ("ACTIVE".equals(request.status().name())) stores.requireActive(merchantId, false);
    return catalog.updateStatus(merchantId, productId, request, correlationId, causationId);
  }

  public CatalogProductV1 updateTranslation(Long merchantId, Long productId, String locale,
                                            CatalogTranslationUpdateRequest request,
                                            String correlationId, String causationId) {
    return catalog.updateTranslation(merchantId, productId, locale, request,
      correlationId, causationId);
  }
}
