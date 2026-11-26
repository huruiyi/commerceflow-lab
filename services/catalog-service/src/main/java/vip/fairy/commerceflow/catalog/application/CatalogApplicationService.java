package vip.fairy.commerceflow.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.catalog.api.CatalogPriceUpdateRequest;
import vip.fairy.commerceflow.catalog.api.CatalogCreateProductRequest;
import vip.fairy.commerceflow.catalog.api.CatalogProductSnapshotV1;
import vip.fairy.commerceflow.catalog.api.CatalogMonitorProfile;
import vip.fairy.commerceflow.catalog.api.CatalogProductV1;
import vip.fairy.commerceflow.catalog.api.CatalogProfileUpdateRequest;
import vip.fairy.commerceflow.catalog.api.CatalogStatusUpdateRequest;
import vip.fairy.commerceflow.catalog.api.CatalogTranslationUpdateRequest;
import vip.fairy.commerceflow.catalog.domain.Product;
import vip.fairy.commerceflow.catalog.domain.ProductTranslation;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogEventOutboxMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSkuMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.ProductMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.ProductTranslationMapper;

import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CatalogApplicationService {

  private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}(?:-[A-Z]{2})?$");

  private final ProductMapper products;
  private final ProductTranslationMapper translations;
  private final CatalogEventOutboxMapper outbox;
  private final CatalogSkuMapper skus;
  private final ObjectMapper mapper;

  public CatalogApplicationService(ProductMapper products, ProductTranslationMapper translations,
                                   CatalogEventOutboxMapper outbox, CatalogSkuMapper skus, ObjectMapper mapper) {
    this.products = products;
    this.translations = translations;
    this.outbox = outbox;
    this.skus = skus;
    this.mapper = mapper;
  }

  public List<CatalogProductV1> list(String requestedLocale) {
    String locale = locale(requestedLocale);
    return products.selectList(new LambdaQueryWrapper<Product>()
        .eq(Product::getLifecycleStatus, "ACTIVE").orderByAsc(Product::getId))
      .stream().map(product -> CatalogProductV1.from(product, translation(product.getId(), locale)))
      .toList();
  }

  public CatalogProductV1 detail(Long productId, String requestedLocale) {
    Product product = active(productId);
    return CatalogProductV1.from(product, translation(productId, locale(requestedLocale)))
      .withMonitorProfile(monitorProfile(product));
  }

  private CatalogMonitorProfile monitorProfile(Product product) {
    var data = product.getMonitorProfile();
    return data == null ? null : mapper.readValue(data, CatalogMonitorProfile.class);
  }

  @Transactional
  public CatalogProductV1 updateMonitorProfile(Long merchantId, Long productId, CatalogMonitorProfile.Update request) {
    var product = owned(merchantId, productId);
    var profile = request.profile();
    var ports = profile.ports().stream().map(String::strip).map(port -> port.toUpperCase(Locale.ROOT))
      .distinct().sorted().toList();
    var clean = new CatalogMonitorProfile(profile.brand().strip(), profile.screenSizeInches(), profile.widthPixels(),
      profile.heightPixels(), profile.panel().strip().toUpperCase(Locale.ROOT), profile.refreshRateHz(), ports);
    requireUpdated(products.updateMonitorProfile(merchantId, productId, request.expectedVersion(),
      mapper.writeValueAsString(clean)));
    return changed(owned(merchantId, productId), CatalogProductEventType.PRODUCT_PROFILE_UPDATED, null, null, null)
      .withMonitorProfile(clean);
  }

  public List<CatalogProductV1> lookup(List<Long> productIds, String requestedLocale) {
    var ids = new LinkedHashSet<>(productIds);
    var found = sellable(products.selectBatchIds(ids));
    String locale = locale(requestedLocale);
    Map<Long, ProductTranslation> localized = Map.of();
    if ("zh-CN".equals(locale) && !found.isEmpty()) {
      localized = translations.selectList(new LambdaQueryWrapper<ProductTranslation>()
          .in(ProductTranslation::getProductId, found.stream().map(Product::getId).toList())
          .eq(ProductTranslation::getLocale, locale))
        .stream().collect(Collectors.toMap(ProductTranslation::getProductId, Function.identity()));
    }
    var translationsById = localized;
    return found.stream()
      .map(product -> CatalogProductV1.from(product, translationsById.get(product.getId())))
      .toList();
  }

  public List<CatalogProductSnapshotV1> snapshots(List<Long> productIds) {
    List<Long> uniqueIds = new LinkedHashSet<>(productIds).stream().toList();
    Map<Long, Product> found = sellable(products.selectBatchIds(uniqueIds)).stream()
      .collect(Collectors.toMap(Product::getId, Function.identity()));
    if (found.size() != uniqueIds.size()) {
      throw new ResourceNotFoundException("One or more active products were not found");
    }
    return uniqueIds.stream().map(found::get).map(CatalogProductSnapshotV1::from).toList();
  }

  public List<CatalogProductV1> merchantList(Long merchantId) {
    return products.selectForMerchant(merchantId).stream()
      .map(this::merchantProduct).toList();
  }

  @Transactional(readOnly = true)
  public CatalogProductV1.Page merchantPage(Long merchantId, int requestedPage, int size) {
    long total = products.countForMerchant(merchantId);
    if (total == 0) {
      return new CatalogProductV1.Page(List.of(), 0, 0, size);
    }
    int page = (int) Math.min(requestedPage, (total - 1) / size);
    var rows = products.selectPageForMerchant(merchantId, size, (long) page * size).stream()
      .map(this::merchantProduct).toList();
    return new CatalogProductV1.Page(rows, total, page, size);
  }

  public CatalogProductV1 merchantDetail(Long merchantId, Long productId) {
    return merchantProduct(owned(merchantId, productId));
  }

  private CatalogProductV1 merchantProduct(Product product) {
    return CatalogProductV1.from(product, null).withMonitorProfile(monitorProfile(product));
  }

  @Transactional
  public CatalogProductV1 create(Long merchantId, CatalogCreateProductRequest request) {
    String key = request.requestId().toString();
    String fingerprint;
    try {
      fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(request)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
    var product = new Product(merchantId, key, fingerprint, request.name().strip(),
      request.description().strip(), request.unitPrice(), request.imageUrl().strip());
    int inserted;
    try {
      inserted = products.insertListing(product);
    } catch (DuplicateKeyException exception) {
      inserted = 0;
    }
    if (inserted == 0) {
      var existing = products.findCreation(merchantId, key);
      if (existing == null || !fingerprint.equals(existing.getCreateFingerprint())) {
        throw new CatalogVersionConflictException("Product creation key has been used with different fields");
      }
      return CatalogProductV1.from(existing, null);
    }
    skus.insertDefault(product.getId(), merchantId);
    return changed(owned(merchantId, product.getId()), CatalogProductEventType.PRODUCT_CREATED,
      null, key, key);
  }

  @Transactional
  public CatalogProductV1 updateProfile(Long merchantId, Long productId,
                                        CatalogProfileUpdateRequest request,
                                        String correlationId, String causationId) {
    owned(merchantId, productId);
    requireUpdated(products.updateProfile(merchantId, productId, request.expectedVersion(),
      request.name().trim(), request.brief().trim(), request.description().trim(),
      request.imageUrl().trim(), request.thumbnailUrl().trim()));
    return changed(owned(merchantId, productId), CatalogProductEventType.PRODUCT_PROFILE_UPDATED,
      null, correlationId, causationId);
  }

  @Transactional
  public CatalogProductV1 updatePrice(Long merchantId, Long productId,
                                      CatalogPriceUpdateRequest request,
                                      String correlationId, String causationId) {
    owned(merchantId, productId);
    requireUpdated(products.updatePrice(merchantId, productId, request.expectedVersion(),
      request.unitPrice()));
    return changed(owned(merchantId, productId), CatalogProductEventType.PRODUCT_PRICE_CHANGED,
      null, correlationId, causationId);
  }

  @Transactional
  public CatalogProductV1 updateStatus(Long merchantId, Long productId,
                                       CatalogStatusUpdateRequest request,
                                       String correlationId, String causationId) {
    owned(merchantId, productId);
    requireUpdated(products.updateStatus(merchantId, productId, request.expectedVersion(),
      request.status().name()));
    return changed(owned(merchantId, productId), CatalogProductEventType.PRODUCT_STATUS_CHANGED,
      null, correlationId, causationId);
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
    append(CatalogProductEventV1.translation(product, locale, request.name().trim(),
      request.brief().trim(), request.description().trim(), correlationId, causationId));
    return CatalogProductV1.from(product, null);
  }

  private CatalogProductV1 changed(Product product, CatalogProductEventType type, String locale,
                                   String correlationId, String causationId) {
    append(CatalogProductEventV1.from(product, type, locale, correlationId, causationId));
    return CatalogProductV1.from(product, null);
  }

  private void append(CatalogProductEventV1 event) {
    event.validate();
    try {
      outbox.insert(event, mapper.writeValueAsString(event));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Catalog event cannot be serialized", exception);
    }
  }

  void publishSku(Product variant, String status, boolean created) {
    var type = created ? CatalogProductEventType.PRODUCT_CREATED : CatalogProductEventType.PRODUCT_PRICE_CHANGED;
    append(CatalogProductEventV1.from(variant, type, null, null, null, status));
  }

  void reprojectListing(Product listing) {
    requireUpdated(products.advanceVersion(listing.getMerchantId(), listing.getId(), listing.getBusinessVersion()));
    changed(owned(listing.getMerchantId(), listing.getId()), CatalogProductEventType.PRODUCT_PROFILE_UPDATED,
      null, null, null);
  }

  private Product active(Long productId) {
    Product product = products.selectById(productId);
    if (product == null) {
      throw new ResourceNotFoundException("Product not found");
    }
    return sellable(List.of(product)).stream().findFirst()
      .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
  }

  private List<Product> sellable(List<Product> rows) {
    var aliases = rows.stream().filter(row -> row.getListingProductId() != null).toList();
    if (aliases.isEmpty()) {
      return rows.stream().filter(row -> "ACTIVE".equals(row.getLifecycleStatus())).toList();
    }
    var activeListings = products.selectBatchIds(aliases.stream().map(Product::getListingProductId).distinct().toList())
      .stream().filter(row -> "ACTIVE".equals(row.getLifecycleStatus()))
      .map(Product::getId).collect(Collectors.toSet());
    var activeSkus = skus.forStockIds(aliases.stream().map(Product::getId).toList()).stream()
      .filter(row -> "ACTIVE".equals(row.lifecycleStatus()) && activeListings.contains(row.productId()))
      .map(CatalogSkuMapper.Row::stockProductId).collect(Collectors.toSet());
    return rows.stream().filter(row -> row.getListingProductId() == null
        ? "ACTIVE".equals(row.getLifecycleStatus()) : activeSkus.contains(row.getId()))
      .map(Product::asSellable).toList();
  }

  private Product owned(Long merchantId, Long productId) {
    Product product = products.selectById(productId);
    if (product == null || product.getListingProductId() != null || !merchantId.equals(product.getMerchantId())) {
      throw new ResourceNotFoundException("Product not found");
    }
    return product;
  }

  private ProductTranslation translation(Long productId, String locale) {
    if (!"zh-CN".equals(locale)) {
      return null;
    }
    return translations.selectOne(new LambdaQueryWrapper<ProductTranslation>()
      .eq(ProductTranslation::getProductId, productId)
      .eq(ProductTranslation::getLocale, locale));
  }

  private void requireUpdated(int updated) {
    if (updated != 1) {
      throw new CatalogVersionConflictException(
        "Product changed since it was read; reload it before retrying");
    }
  }

  static String locale(String requestedLocale) {
    return requestedLocale != null && requestedLocale.toLowerCase().startsWith("zh")
      ? "zh-CN" : "en";
  }
}
