package vip.fairy.commerceflow.commerce.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductSnapshotV1;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.domain.Product;
import vip.fairy.commerceflow.commerce.catalog.domain.ProductTranslation;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.ProductMapper;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.ProductTranslationMapper;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CatalogContractService {

  private final ProductMapper products;
  private final ProductTranslationMapper translations;

  public CatalogContractService(ProductMapper products, ProductTranslationMapper translations) {
    this.products = products;
    this.translations = translations;
  }

  public List<CatalogProductV1> list(String requestedLocale) {
    String locale = locale(requestedLocale);
    return products.selectList(new LambdaQueryWrapper<Product>()
        .eq(Product::getLifecycleStatus, "ACTIVE")
        .orderByAsc(Product::getId))
      .stream()
      .map(product -> CatalogProductV1.from(product, translation(product.getId(), locale)))
      .toList();
  }

  public CatalogProductV1 detail(Long productId, String requestedLocale) {
    Product product = active(productId);
    return CatalogProductV1.from(product, translation(productId, locale(requestedLocale)));
  }

  public List<CatalogProductSnapshotV1> snapshots(List<Long> productIds) {
    List<Long> uniqueIds = new LinkedHashSet<>(productIds).stream().toList();
    Map<Long, Product> found = products.selectBatchIds(uniqueIds).stream()
      .filter(product -> "ACTIVE".equals(product.getLifecycleStatus()))
      .collect(Collectors.toMap(Product::getId, Function.identity()));
    if (found.size() != uniqueIds.size()) {
      throw new ResourceNotFoundException("One or more active products were not found");
    }
    return uniqueIds.stream().map(found::get).map(CatalogProductSnapshotV1::from).toList();
  }

  private Product active(Long productId) {
    Product product = products.selectById(productId);
    if (product == null || !"ACTIVE".equals(product.getLifecycleStatus())) {
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

  private String locale(String requestedLocale) {
    return requestedLocale != null && requestedLocale.toLowerCase().startsWith("zh") ? "zh-CN" : "en";
  }
}
