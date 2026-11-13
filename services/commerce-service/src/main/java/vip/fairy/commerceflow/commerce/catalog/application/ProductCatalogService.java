package vip.fairy.commerceflow.commerce.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.catalog.domain.Product;
import vip.fairy.commerceflow.commerce.catalog.domain.ProductTranslation;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.ProductMapper;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.ProductTranslationMapper;
import vip.fairy.commerceflow.commerce.catalog.api.ProductResponse;
import vip.fairy.commerceflow.commerce.review.api.ProductReviewResponse;
import vip.fairy.commerceflow.commerce.review.infrastructure.ReviewMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@Service
public class ProductCatalogService {
  public static final String CACHE_KEY = "commerceflow:catalog:products:v1";
  private final ProductMapper products;
  private final ProductTranslationMapper translations;
  private final ReviewMapper reviews;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public ProductCatalogService(ProductMapper products,
                               ProductTranslationMapper translations,
                               ReviewMapper reviews,
                               StringRedisTemplate redis,
                               ObjectMapper mapper) {
    this.products = products;
    this.translations = translations;
    this.reviews = reviews;
    this.redis = redis;
    this.mapper = mapper;
  }

  public List<ProductResponse> list(String requestedLocale) {
    String locale = requestedLocale != null && requestedLocale.toLowerCase().startsWith("zh")
      ? "zh-CN"
      : "en";
    String key = CACHE_KEY + ":" + locale;
    try {
      String cached = redis.opsForValue().get(key);
      if (cached != null) {
        return mapper.readValue(cached, new TypeReference<>() {
        });
      }
    } catch (Exception ignored) {
    }
    List<ProductResponse> result = products.selectList(
        new LambdaQueryWrapper<Product>().orderByAsc(Product::getId))
      .stream()
      .map(product -> response(product, locale))
      .toList();
    try {
      redis.opsForValue().set(key, mapper.writeValueAsString(result), Duration.ofMinutes(5));
    } catch (Exception ignored) {
    }
    return result;
  }

  public void invalidate() {
    try {
      Set<String> keys = redis.keys(CACHE_KEY + ":*");
      if (keys != null) {
        redis.delete(keys);
      }
    } catch (Exception ignored) {
    }
  }

  private ProductResponse response(Product product, String locale) {
    ProductTranslation translation = null;
    if ("zh-CN".equals(locale)) {
      translation = translations.selectOne(new LambdaQueryWrapper<ProductTranslation>()
        .eq(ProductTranslation::getProductId, product.getId())
        .eq(ProductTranslation::getLocale, locale));
    }
    List<ProductReviewResponse> productReviews = reviews.reviews(product.getId()).stream()
      .map(ProductReviewResponse::from)
      .toList();
    return ProductResponse.from(
      product,
      translation,
      reviews.average(product.getId()),
      reviews.count(product.getId()),
      productReviews);
  }
}
