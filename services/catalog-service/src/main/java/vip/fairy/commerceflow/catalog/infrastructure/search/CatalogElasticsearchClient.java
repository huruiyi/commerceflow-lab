package vip.fairy.commerceflow.catalog.infrastructure.search;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.catalog.api.CatalogProductV1;
import vip.fairy.commerceflow.catalog.application.CatalogSearchService.Criteria;
import vip.fairy.commerceflow.catalog.application.CatalogSearchService.Page;
import vip.fairy.commerceflow.catalog.application.CatalogSkuSearch.Option;

@Component
@ConditionalOnProperty(name = "commerceflow.catalog-search.elasticsearch.enabled", havingValue = "true")
public class CatalogElasticsearchClient {

  private static final int RESULT_WINDOW = 10_000;
  private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
  private static final String INDEX_MAPPING = """
    {"settings":{"number_of_shards":1,"number_of_replicas":0},
     "mappings":{"dynamic":"strict","properties":{
       "productId":{"type":"long"},"merchantId":{"type":"long"},"priceCents":{"type":"long"},
       "active":{"type":"boolean"},"textZh":{"type":"wildcard"},"textEn":{"type":"wildcard"},
       "nameZh":{"type":"wildcard"},"nameEn":{"type":"wildcard"},
       "relevanceZh":{"type":"text","analyzer":"cjk"},"relevanceEn":{"type":"text","analyzer":"standard"},
       "products":{"type":"object","enabled":false},
       "skus":{"type":"nested","properties":{"priceCents":{"type":"long"},"specifications":{"type":"keyword"}}}
     }}}
    """;
  private static final String RATING_SCRIPT = """
    def rank = params.ranks.get(doc['productId'].value.toString());
    return rank == null ? params.unrated : rank;
    """;

  private final RestClient client;
  private final ObjectMapper json;
  private final String prefix;

  public CatalogElasticsearchClient(ObjectMapper json,
      @Value("${commerceflow.catalog-search.elasticsearch.url:http://localhost:9200}") String url,
      @Value("${commerceflow.catalog-search.elasticsearch.index-prefix:commerceflow-catalog-products}") String prefix) {
    if (!prefix.matches("[a-z][a-z0-9-]{0,79}") || !prefix.startsWith("commerceflow-")) {
      throw new IllegalArgumentException("Invalid CommerceFlow Elasticsearch index prefix");
    }
    var transport = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(750)).build();
    var factory = new JdkClientHttpRequestFactory(transport);
    factory.setReadTimeout(Duration.ofSeconds(4));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    this.json = json;
    this.prefix = prefix;
  }

  public boolean exists(long generation) {
    try {
      client.head().uri(index(generation)).retrieve().toBodilessEntity();
      return true;
    } catch (HttpClientErrorException.NotFound missing) {
      return false;
    }
  }

  public void create(long generation) {
    client.put().uri(index(generation)).contentType(MediaType.APPLICATION_JSON).body(INDEX_MAPPING)
      .retrieve().toBodilessEntity();
  }

  public void remove(long generation) {
    try {
      client.delete().uri(index(generation)).retrieve().toBodilessEntity();
    } catch (HttpClientErrorException.NotFound missing) {
      // Retrying a partially built generation can start with an absent index.
    }
  }

  public void write(long generation, List<Document> documents, boolean waitForRefresh) {
    var body = new StringBuilder();
    for (var document : documents) {
      body.append(json.writeValueAsString(Map.of("index", Map.of("_id", Long.toString(document.productId())))))
        .append('\n').append(json.writeValueAsString(document)).append('\n');
    }
    var result = client.post().uri(index(generation) + "/_bulk?refresh=" + (waitForRefresh ? "wait_for" : "false"))
      .contentType(NDJSON).body(body.toString().getBytes(StandardCharsets.UTF_8)).retrieve().body(String.class);
    if (result == null) throw new RestClientException("Missing Elasticsearch bulk response");
    var response = json.readTree(result);
    if (response.path("errors").asBoolean(true)) {
      for (var item : response.path("items")) {
        var operation = item.path("index");
        if (operation.has("error")) {
          throw new RestClientException("Elasticsearch rejected a catalog document: " + operation.path("status").asInt()
            + " " + operation.path("error").path("type").asText());
        }
      }
      throw new RestClientException("Incomplete Elasticsearch bulk response");
    }
  }

  public void refresh(long generation) {
    client.post().uri(index(generation) + "/_refresh").retrieve().toBodilessEntity();
  }

  public List<IndexedDocument> documentsAfter(long generation, long afterId, int size) {
    var request = Map.of("query", Map.of("range", Map.of("productId", Map.of("gt", afterId))),
      "sort", List.of(Map.of("productId", "asc")), "size", size);
    return searchHits(generation, request).path("hits").valueStream()
      .map(hit -> new IndexedDocument(hit.path("_id").asText(), hit.path("_source"))).toList();
  }

  public boolean matches(IndexedDocument indexed, Document expected) {
    return indexed.id().equals(Long.toString(expected.productId()))
      && indexed.source().equals(json.readTree(json.writeValueAsString(expected)));
  }

  public Optional<Page> search(long generation, Criteria criteria, int requestedPage) {
    long offset = (long) requestedPage * criteria.size();
    if (offset + criteria.size() > RESULT_WINDOW) return Optional.empty();

    var response = execute(generation, criteria, offset);
    long total = response.path("total").path("value").asLong();
    int page = total == 0 ? 0 : (int) Math.min(requestedPage, (total - 1) / criteria.size());
    if (total > 0 && page != requestedPage) {
      response = execute(generation, criteria, (long) page * criteria.size());
    }
    List<CatalogProductV1> products = new ArrayList<>();
    for (var hit : response.path("hits")) {
      var product = hit.path("_source").path("products").path(criteria.locale());
      products.add(json.treeToValue(product, CatalogProductV1.class));
    }
    return Optional.of(new Page(products, total, page, criteria.size()));
  }

  private JsonNode execute(long generation, Criteria criteria, long offset) {
    var request = Map.of("query", query(criteria), "sort", sort(criteria), "track_total_hits", true,
      "from", offset, "size", criteria.size(), "_source", List.of("products"));
    return searchHits(generation, request);
  }

  private JsonNode searchHits(long generation, Map<String, Object> request) {
    var result = client.post().uri(index(generation) + "/_search").contentType(MediaType.APPLICATION_JSON)
      .body(json.writeValueAsString(request)).retrieve().body(String.class);
    if (result == null) throw new RestClientException("Missing Elasticsearch search response");
    var parsed = json.readTree(result);
    if (parsed.path("timed_out").asBoolean() || parsed.path("_shards").path("failed").asInt() != 0) {
      throw new RestClientException("Elasticsearch returned incomplete catalog results");
    }
    return parsed.path("hits");
  }

  private Map<String, Object> query(Criteria criteria) {
    String language = criteria.locale().equals("zh-CN") ? "Zh" : "En";
    List<Map<String, Object>> filters = new ArrayList<>();
    filters.add(Map.of("term", Map.of("active", true)));
    filters.add(Map.of("terms", Map.of("merchantId", criteria.merchantIds())));
    var sku = skuFilters(criteria);
    if (!sku.isEmpty()) {
      filters.add(Map.of("nested", Map.of("path", "skus", "query", Map.of("bool", Map.of("filter", sku)))));
    }
    String field = "text" + language;
    var positive = criteria.terms().stream().map(term -> substring(field, term)).toList();
    if (criteria.match().equals("any") && !positive.isEmpty()) {
      filters.add(Map.of("bool", Map.of("should", positive, "minimum_should_match", 1)));
    } else {
      filters.addAll(positive);
    }
    Map<String, Object> clauses = new HashMap<>();
    clauses.put("filter", filters);
    if (!criteria.excluded().isEmpty()) {
      clauses.put("must_not", criteria.excluded().stream().map(term -> substring(field, term)).toList());
    }
    if (!positive.isEmpty()) {
      var nameMatch = substring("name" + language, criteria.terms().getFirst());
      clauses.put("should", List.of(
        Map.of("constant_score", Map.of("filter", nameMatch, "boost", 5)),
        Map.of("match", Map.of("relevance" + language, normalize(criteria.query())))
      ));
    }
    return Map.of("bool", clauses);
  }

  private List<Map<String, Object>> sort(Criteria criteria) {
    List<Map<String, Object>> sort = new ArrayList<>();
    switch (criteria.sort()) {
      case "price-asc" -> sort.add(skuSort(criteria, "asc"));
      case "price-desc" -> sort.add(skuSort(criteria, "desc"));
      case "rating" -> {
        if (!criteria.ratedIds().isEmpty()) {
          Map<String, Integer> ranks = new HashMap<>();
          for (var id : criteria.ratedIds()) ranks.putIfAbsent(id.toString(), ranks.size());
          sort.add(Map.of("_script", Map.of("type", "number", "order", "asc", "script",
            Map.of("source", RATING_SCRIPT, "params", Map.of("ranks", ranks, "unrated", ranks.size())))));
        } else if (!criteria.terms().isEmpty()) {
          sort.add(Map.of("_score", "desc"));
        }
      }
      default -> {
        if (!criteria.terms().isEmpty()) sort.add(Map.of("_score", "desc"));
      }
    }
    sort.add(Map.of("productId", "asc"));
    return sort;
  }

  private Map<String, Object> skuSort(Criteria criteria, String order) {
    var nested = new HashMap<String, Object>();
    nested.put("path", "skus");
    var filters = skuFilters(criteria);
    if (!filters.isEmpty()) nested.put("filter", Map.of("bool", Map.of("filter", filters)));
    return Map.of("skus.priceCents", Map.of("order", order, "mode", "min", "nested", nested));
  }

  private List<Map<String, Object>> skuFilters(Criteria criteria) {
    List<Map<String, Object>> filters = new ArrayList<>();
    Map<String, Long> range = new HashMap<>();
    if (criteria.minPrice() != null) range.put("gte", cents(criteria.minPrice()));
    if (criteria.maxPrice() != null) range.put("lte", cents(criteria.maxPrice()));
    if (!range.isEmpty()) filters.add(Map.of("range", Map.of("skus.priceCents", range)));
    for (String token : criteria.skuTokens()) {
      filters.add(Map.of("term", Map.of("skus.specifications", token)));
    }
    return filters;
  }

  private Map<String, Object> substring(String field, String value) {
    String literal = normalize(value).replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    return Map.of("wildcard", Map.of(field, "*" + literal + "*"));
  }

  private String index(long generation) {
    return "/" + prefix + "-" + generation;
  }

  public static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
  }

  public static long cents(BigDecimal price) {
    return price.movePointRight(2).longValueExact();
  }

  /** 搜索字段与返回给门户的两种语言快照。 */
  public record Document(long productId, long merchantId, long priceCents, boolean active, String textZh, String textEn,
                         String nameZh, String nameEn, String relevanceZh, String relevanceEn,
                         Map<String, CatalogProductV1> products, List<Option> skus) {
  }

  /** Elasticsearch 返回的原始文档编号及内容，用于索引核对。 */
  public record IndexedDocument(String id, JsonNode source) {

    public long productId() {
      return source.path("productId").asLong();
    }

    public Long businessVersion() {
      var version = source.path("products").path("zh-CN").path("businessVersion");
      return version.isIntegralNumber() ? version.asLong() : null;
    }
  }
}
