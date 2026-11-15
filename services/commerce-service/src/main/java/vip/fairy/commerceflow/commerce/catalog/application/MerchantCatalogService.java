package vip.fairy.commerceflow.commerce.catalog.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogPriceUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogProfileUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogStatusUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogTranslationUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.domain.Product;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.CatalogEventOutboxMapper;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.ProductMapper;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.ProductTranslationMapper;
import vip.fairy.commerceflow.commerce.shared.application.CatalogSnapshotChanged;
import vip.fairy.commerceflow.commerce.shared.application.OptimisticVersionConflictException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class MerchantCatalogService {

  private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}(?:-[A-Z]{2})?$");
  private final ProductMapper products;
  private final ProductTranslationMapper translations;
  private final CatalogEventOutboxMapper outbox;
  private final ProductCatalogService cache;
  private final ObjectMapper mapper;
  private final ApplicationEventPublisher events;

  public MerchantCatalogService(ProductMapper products,
                                ProductTranslationMapper translations,
                                CatalogEventOutboxMapper outbox,
                                ProductCatalogService cache,
                                ObjectMapper mapper,
                                ApplicationEventPublisher events) {
    this.products = products;
    this.translations = translations;
    this.outbox = outbox;
    this.cache = cache;
    this.mapper = mapper;
    this.events = events;
  }

  public List<CatalogProductV1> list(Long merchantId) {
    return products.selectForMerchant(merchantId).stream()
      .map(product -> CatalogProductV1.from(product, null))
      .toList();
  }

  @Transactional
  public CatalogProductV1 updateProfile(Long merchantId, Long productId,
                                        CatalogProfileUpdateRequest request,
                                        String correlationId, String causationId) {
    owned(merchantId, productId);
    int updated = products.updateProfile(
      merchantId, productId, request.expectedVersion(), request.name().trim(),
      request.brief().trim(), request.description().trim(), request.imageUrl().trim(),
      request.thumbnailUrl().trim());
    requireUpdated(updated);
    Product product = owned(merchantId, productId);
    append(product, CatalogProductEventType.PRODUCT_PROFILE_UPDATED, null, correlationId, causationId);
    updateInventorySnapshot(product);
    cache.invalidate();
    return CatalogProductV1.from(product, null);
  }

  @Transactional
  public CatalogProductV1 updatePrice(Long merchantId, Long productId,
                                      CatalogPriceUpdateRequest request,
                                      String correlationId, String causationId) {
    owned(merchantId, productId);
    requireUpdated(products.updatePrice(
      merchantId, productId, request.expectedVersion(), request.unitPrice()));
    Product product = owned(merchantId, productId);
    append(product, CatalogProductEventType.PRODUCT_PRICE_CHANGED, null, correlationId, causationId);
    updateInventorySnapshot(product);
    cache.invalidate();
    return CatalogProductV1.from(product, null);
  }

  @Transactional
  public CatalogProductV1 updateStatus(Long merchantId, Long productId,
                                       CatalogStatusUpdateRequest request,
                                       String correlationId, String causationId) {
    owned(merchantId, productId);
    requireUpdated(products.updateStatus(
      merchantId, productId, request.expectedVersion(), request.status().name()));
    Product product = owned(merchantId, productId);
    append(product, CatalogProductEventType.PRODUCT_STATUS_CHANGED, null, correlationId, causationId);
    cache.invalidate();
    return CatalogProductV1.from(product, null);
  }

  @Transactional
  public CatalogProductV1 updateTranslation(Long merchantId, Long productId, String locale,
                                            CatalogTranslationUpdateRequest request,
                                            String correlationId, String causationId) {
    if (!LOCALE.matcher(locale).matches()) {
      throw new IllegalArgumentException("locale must use language or language-REGION format");
    }
    owned(merchantId, productId);
    requireUpdated(products.advanceVersion(merchantId, productId, request.expectedVersion()));
    translations.upsert(productId, locale, request.name().trim(), request.brief().trim(),
      request.description().trim());
    Product product = owned(merchantId, productId);
    append(CatalogProductEventV1.translation(
      product, locale, request.name().trim(), request.brief().trim(), request.description().trim(),
      correlationId, causationId));
    cache.invalidate();
    return CatalogProductV1.from(product, null);
  }

  private Product owned(Long merchantId, Long productId) {
    Product product = products.selectById(productId);
    if (product == null || !merchantId.equals(product.getMerchantId())) {
      throw new ResourceNotFoundException("Product not found");
    }
    return product;
  }

  private void requireUpdated(int updated) {
    if (updated != 1) {
      throw new OptimisticVersionConflictException(
        "CATALOG_VERSION_CONFLICT",
        "Product changed since it was read; reload it before retrying");
    }
  }

  private void append(Product product, CatalogProductEventType type, String locale,
                      String correlationId, String causationId) {
    append(CatalogProductEventV1.from(product, type, locale, correlationId, causationId));
  }

  private void append(CatalogProductEventV1 event) {
    event.validate();
    try {
      outbox.insert(event, mapper.writeValueAsString(event));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Catalog event cannot be serialized", exception);
    }
  }

  private void updateInventorySnapshot(Product product) {
    events.publishEvent(new CatalogSnapshotChanged(
      product.getId(), product.getMerchantId(), product.getName(),
      product.getThumbnailUrl(), product.getPrice()));
  }
}
