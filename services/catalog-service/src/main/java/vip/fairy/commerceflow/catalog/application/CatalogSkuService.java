package vip.fairy.commerceflow.catalog.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.catalog.api.CatalogSkuV1;
import vip.fairy.commerceflow.catalog.domain.Product;
import vip.fairy.commerceflow.catalog.domain.ProductLifecycleStatus;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSkuMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSkuMapper.Row;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.ProductMapper;

@Service
public class CatalogSkuService {

  private static final TypeReference<Map<String, String>> SPECS = new TypeReference<>() {
  };
  private final ProductMapper products;
  private final CatalogSkuMapper skus;
  private final ObjectMapper json;
  private final CatalogApplicationService catalog;

  CatalogSkuService(ProductMapper products, CatalogSkuMapper skus, ObjectMapper json,
                    CatalogApplicationService catalog) {
    this.products = products;
    this.skus = skus;
    this.json = json;
    this.catalog = catalog;
  }

  @Scheduled(initialDelayString = "5s", fixedDelayString = "30s")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void backfillImportedProducts() {
    skus.backfillDefaults();
    for (var unbound : skus.unbound()) {
      var listing = lockOwned(unbound.merchantId(), unbound.productId());
      var row = skus.find(unbound.merchantId(), unbound.productId(), unbound.id());
      if (row.stockProductId() == null) {
        materialize(row, listing);
        catalog.reprojectListing(listing);
      }
    }
  }

  @Transactional(readOnly = true)
  public List<CatalogSkuV1> list(long merchant, long productId) {
    var product = owned(merchant, productId);
    return skus.list(merchant, productId).stream().map(row -> view(row, product)).toList();
  }

  @Transactional(readOnly = true)
  public List<CatalogSkuV1> sellable(long productId) {
    var product = products.selectById(productId);
    if (product == null || product.getListingProductId() != null || !"ACTIVE".equals(product.getLifecycleStatus())) {
      return List.of();
    }
    return skus.list(product.getMerchantId(), productId).stream()
      .filter(row -> row.stockProductId() != null && (row.defaultSku() || "ACTIVE".equals(row.lifecycleStatus())))
      .map(row -> view(row, product)).toList();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CatalogSkuV1 create(long merchant, long productId, CatalogSkuV1.Create request) {
    var product = lockOwned(merchant, productId);
    var code = code(request.code(), null);
    var specs = specifications(request.specifications());
    String specKey = specs.key();
    String specification = specs.json();
    String fingerprint = digest(json.writeValueAsString(
      List.of(productId, code, specification, request.unitPrice().stripTrailingZeros(), request.status())));
    String requestId = request.requestId().toString();
    var existing = skus.findRequest(merchant, requestId);
    if (existing != null) return replay(existing, fingerprint, product);
    try {
      skus.insert(new Row(null, productId, merchant, code, specKey, specification, false, request.unitPrice(),
        request.status().name(), 1, requestId, fingerprint, null));
    } catch (DuplicateKeyException duplicate) {
      return replay(skus.findRequest(merchant, requestId), fingerprint, product);
    }
    var inserted = skus.findRequest(merchant, requestId);
    materialize(inserted, product);
    catalog.reprojectListing(product);
    return view(skus.findRequest(merchant, requestId), product);
  }

  private Specifications specifications(Map<String, String> input) {
    var display = new TreeMap<String, String>();
    var canonical = new TreeMap<String, String>();
    input.forEach((name, value) -> {
      String key = name.strip();
      String normalized = key.toLowerCase(Locale.ROOT);
      if (canonical.putIfAbsent(normalized, value.strip().toLowerCase(Locale.ROOT)) != null) {
        throw new IllegalArgumentException("Duplicate specification name");
      }
      display.put(key, value.strip());
    });
    return new Specifications(digest(json.writeValueAsString(canonical)), json.writeValueAsString(display));
  }

  private record Specifications(String key, String json) {
  }

  @Transactional
  public CatalogSkuV1 update(long merchant, long productId, long skuId, CatalogSkuV1.Update request) {
    var product = lockOwned(merchant, productId);
    var row = skus.find(merchant, productId, skuId);
    if (row == null) throw new ResourceNotFoundException("SKU not found");
    String code = code(request.code(), row.code());
    if (row.defaultSku() && (product.getPrice().compareTo(request.unitPrice()) != 0
        || !product.getLifecycleStatus().equals(request.status().name()))) {
      throw new CatalogVersionConflictException("Update default SKU price and status on the product");
    }
    if (!row.defaultSku() && request.unitPrice().signum() <= 0) {
      throw new IllegalArgumentException("SKU price must be positive");
    }
    if (request.specifications() != null && !row.defaultSku()) {
      throw new IllegalArgumentException("Specifications of existing variant SKUs are fixed");
    }
    var specs = request.specifications() == null ? null : specifications(request.specifications());
    int updated;
    try {
      updated = skus.update(skuId, request.expectedVersion(), code, row.defaultSku() ? null : request.unitPrice(),
        row.defaultSku() ? null : request.status().name(), specs == null ? null : specs.key(),
        specs == null ? null : specs.json());
    } catch (DuplicateKeyException duplicate) {
      throw new CatalogVersionConflictException("SKU code already exists in this store");
    }
    if (updated != 1) throw new CatalogVersionConflictException("SKU changed since it was read");
    if (!row.defaultSku()) {
      var latest = skus.find(merchant, productId, skuId);
      if (latest.stockProductId() == null) {
        materialize(latest, product);
      } else {
        var variant = products.selectById(latest.stockProductId());
        if (products.updatePrice(merchant, variant.getId(), variant.getBusinessVersion(), latest.unitPrice()) != 1) {
          throw new CatalogVersionConflictException("SKU stock identity changed");
        }
        catalog.publishSku(products.selectById(variant.getId()), latest.lifecycleStatus(), false);
      }
    }
    if (!row.defaultSku() || specs != null) catalog.reprojectListing(product);
    return view(skus.find(merchant, productId, skuId), product);
  }

  private void materialize(Row row, Product listing) {
    var specifications = json.readValue(row.specifications(), SPECS);
    String caption = String.join(" / ", specifications.values());
    String productName = truncate(listing.getName(), 77) + " · " + truncate(caption, 78);
    var variant = Product.variant(listing, productName, row.unitPrice());
    products.insert(variant);
    if (skus.bindStock(row.id(), variant.getId()) != 1) {
      throw new CatalogVersionConflictException("SKU stock identity changed");
    }
    catalog.publishSku(variant, row.lifecycleStatus(), true);
  }

  private CatalogSkuV1 replay(Row row, String fingerprint, Product product) {
    if (row == null || !fingerprint.equals(row.createFingerprint())) {
      throw new CatalogVersionConflictException("SKU code, specification combination or request already exists");
    }
    return view(row, product);
  }

  private Product owned(long merchant, long productId) {
    return requireListing(merchant, products.selectById(productId));
  }

  private Product lockOwned(long merchant, long productId) {
    return requireListing(merchant, products.lockListing(productId));
  }

  private Product requireListing(long merchant, Product product) {
    if (product == null || product.getListingProductId() != null
      || !Long.valueOf(merchant).equals(product.getMerchantId())) {
      throw new ResourceNotFoundException("Product not found");
    }
    return product;
  }

  private static String truncate(String text, int limit) {
    return text.substring(0, text.offsetByCodePoints(0, Math.min(text.codePointCount(0, text.length()), limit)));
  }

  private CatalogSkuV1 view(Row row, Product product) {
    var price = row.defaultSku() ? product.getPrice() : row.unitPrice();
    var status = row.defaultSku() ? product.getLifecycleStatus() : row.lifecycleStatus();
    return new CatalogSkuV1(row.id(), row.productId(), row.code(), json.readValue(row.specifications(), SPECS),
      row.defaultSku(), price, ProductLifecycleStatus.valueOf(status), row.businessVersion(), row.stockProductId());
  }

  private static String code(String input, String existing) {
    String normalized = input.strip().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z0-9][A-Z0-9._-]{0,63}")
        || (normalized.startsWith("DEFAULT-") && !normalized.equals(existing))) {
      throw new IllegalArgumentException("Invalid or reserved SKU code");
    }
    return normalized;
  }

  private static String digest(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }
}
