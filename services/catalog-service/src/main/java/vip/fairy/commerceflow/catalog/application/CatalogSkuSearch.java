package vip.fairy.commerceflow.catalog.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.catalog.api.CatalogProductV1;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSkuMapper;
import vip.fairy.commerceflow.catalog.infrastructure.search.CatalogElasticsearchClient;

@Component
public class CatalogSkuSearch {

  private static final TypeReference<Map<String, String>> SPECIFICATIONS = new TypeReference<>() {
  };
  private final CatalogSkuMapper skus;
  private final ObjectMapper json;

  CatalogSkuSearch(CatalogSkuMapper skus, ObjectMapper json) {
    this.skus = skus;
    this.json = json;
  }

  Selection selection(String filter) {
    var fields = filters(filter, 4);
    return fields.isEmpty() ? new Selection(List.of(), null)
      : new Selection(tokens(fields), json.writeValueAsString(fields));
  }

  static Map<String, String> filters(String filter, int maxFields) {
    if (filter == null || filter.isBlank()) return Map.of();
    if (filter.length() > 360) throw new IllegalArgumentException("Specification filter is too long");
    var fields = new TreeMap<String, String>();
    for (String entry : filter.split(";", -1)) {
      String[] pair = entry.split("=", 2);
      if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
        throw new IllegalArgumentException("Specification filters use name=value");
      }
      if (fields.putIfAbsent(normalize(pair[0]), normalize(pair[1])) != null || fields.size() > maxFields) {
        throw new IllegalArgumentException("Too many or duplicate specification filters");
      }
    }
    return fields;
  }

  Map<Long, List<Option>> options(List<Long> products) {
    if (products.isEmpty()) return Map.of();
    var options = new HashMap<Long, List<Option>>();
    for (var sku : skus.activeVariants(products)) {
      Map<String, String> specs = json.readValue(sku.specifications(), SPECIFICATIONS);
      options.computeIfAbsent(sku.productId(), ignored -> new ArrayList<>())
        .add(new Option(CatalogElasticsearchClient.cents(sku.unitPrice()), tokens(specs)));
    }
    return options;
  }

  Map<Long, List<String>> specifiedDefaults(List<Long> products) {
    if (products.isEmpty()) return Map.of();
    var defaults = new HashMap<Long, List<String>>();
    for (var sku : skus.specifiedDefaults(products)) {
      defaults.put(sku.productId(), tokens(json.readValue(sku.specifications(), SPECIFICATIONS)));
    }
    return defaults;
  }

  Map<Long, PriceRange> ranges(List<CatalogProductV1> products) {
    var variants = options(products.stream().map(CatalogProductV1::productId).toList());
    Map<Long, PriceRange> ranges = new HashMap<>();
    for (var product : products) {
      var options = variants.get(product.productId());
      if (options == null) continue;
      BigDecimal min = product.unitPrice();
      BigDecimal max = min;
      for (var option : options) {
        var price = BigDecimal.valueOf(option.priceCents(), 2);
        min = min.min(price);
        max = max.max(price);
      }
      ranges.put(product.productId(), new PriceRange(min, max));
    }
    return ranges;
  }

  private List<String> tokens(Map<String, String> specs) {
    return specs.entrySet().stream()
      .map(entry -> json.writeValueAsString(List.of(normalize(entry.getKey()), normalize(entry.getValue())))).toList();
  }

  private static String normalize(String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }

  public record Option(long priceCents, List<String> specifications) {
  }

  public record PriceRange(BigDecimal min, BigDecimal max) {
  }

  public record Facet(String name, String value, long productCount) {

    boolean selectable() {
      return !name.contains("=") && !name.contains(";") && !value.contains(";");
    }
  }

  record Selection(List<String> tokens, String json) {
  }
}
