package vip.fairy.commerceflow.commerce.catalog.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogPriceUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogProfileUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogStatusUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogTranslationUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.client.CatalogServiceClient;

import java.util.List;

@Service
public class MerchantCatalogService {

  private final CatalogServiceClient catalog;

  public MerchantCatalogService(CatalogServiceClient catalog) {
    this.catalog = catalog;
  }

  public List<CatalogProductV1> list(Long merchantId) {
    return catalog.merchantList(merchantId);
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
    return catalog.updateStatus(merchantId, productId, request, correlationId, causationId);
  }

  public CatalogProductV1 updateTranslation(Long merchantId, Long productId, String locale,
                                            CatalogTranslationUpdateRequest request,
                                            String correlationId, String causationId) {
    return catalog.updateTranslation(merchantId, productId, locale, request,
      correlationId, causationId);
  }
}
