package vip.fairy.commerceflow.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import vip.fairy.commerceflow.catalog.api.CatalogProductV1;
import vip.fairy.commerceflow.catalog.application.CatalogSearchService.Criteria;
import vip.fairy.commerceflow.catalog.application.CatalogSearchService.Page;
import vip.fairy.commerceflow.catalog.application.CatalogSkuSearch.Option;
import vip.fairy.commerceflow.catalog.domain.Product;
import vip.fairy.commerceflow.catalog.domain.ProductTranslation;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogElasticsearchMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSearchMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSearchMapper.Change;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.ProductMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.ProductTranslationMapper;
import vip.fairy.commerceflow.catalog.infrastructure.search.CatalogElasticsearchClient;
import vip.fairy.commerceflow.catalog.infrastructure.search.CatalogElasticsearchClient.Document;
import vip.fairy.commerceflow.catalog.infrastructure.search.CatalogElasticsearchClient.IndexedDocument;

@Service
@ConditionalOnProperty(name = "commerceflow.catalog-search.elasticsearch.enabled", havingValue = "true")
public class CatalogElasticsearchSearch {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogElasticsearchSearch.class);

  private final CatalogElasticsearchClient client;
  private final CatalogElasticsearchMapper state;
  private final CatalogSearchMapper search;
  private final ProductMapper products;
  private final ProductTranslationMapper translations;
  private final CatalogSkuSearch skuSearch;
  private final TransactionTemplate transactions;
  private final Counter reads;
  private final Counter fallbacks;
  private volatile boolean synchronizationFailed;

  public CatalogElasticsearchSearch(CatalogElasticsearchClient client, CatalogElasticsearchMapper state,
                                    CatalogSearchMapper search, ProductMapper products,
                                    ProductTranslationMapper translations, CatalogSkuSearch skuSearch,
                                    PlatformTransactionManager transactionManager, MeterRegistry metrics) {
    this.client = client;
    this.state = state;
    this.search = search;
    this.products = products;
    this.translations = translations;
    this.skuSearch = skuSearch;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.reads = metrics.counter("commerceflow.catalog.search.elasticsearch.reads");
    this.fallbacks = metrics.counter("commerceflow.catalog.search.elasticsearch.fallbacks");
  }

  @Scheduled(
    initialDelayString = "${commerceflow.catalog-search.elasticsearch.initial-delay:3s}",
    fixedDelayString = "${commerceflow.catalog-search.elasticsearch.interval:2s}"
  )
  public void consume() {
    try {
      Long expired = transactions.execute(status -> synchronize());
      if (expired != null && expired > 0) client.remove(expired);
      synchronizationFailed = false;
    } catch (RestClientException | JacksonException failure) {
      if (!synchronizationFailed) LOG.warn("Catalog Elasticsearch synchronization interrupted", failure);
      synchronizationFailed = true;
    }
  }

  private Long synchronize() {
    var indexed = state.lockState();
    long catalogGeneration = search.state().generation();
    if (catalogGeneration == 0) return null;

    long generation = indexed.generation();
    if (generation == 0 || catalogGeneration != indexed.catalogGeneration() || !client.exists(generation)) {
      long next = generation + 1;
      rebuild(next, catalogGeneration);
      return next - 2;
    }
    var changes = state.pending(Long.MAX_VALUE);
    if (!changes.isEmpty()) apply(generation, changes, true);
    return null;
  }

  private void rebuild(long generation, long catalogGeneration) {
    client.remove(generation);
    client.create(generation);
    long lastId = 0;
    for (var ids = state.productIdsAfter(lastId); !ids.isEmpty(); ids = state.productIdsAfter(lastId)) {
      client.write(generation, documents(ids), false);
      lastId = ids.getLast();
    }
    long boundary = search.eventBoundary();
    for (var changes = state.pending(boundary); !changes.isEmpty(); changes = state.pending(boundary)) {
      apply(generation, changes, false);
    }
    client.refresh(generation);
    state.switchGeneration(generation, catalogGeneration);
  }

  private void apply(long generation, List<Change> changes, boolean waitForRefresh) {
    var ids = changes.stream().map(Change::productId).distinct().toList();
    client.write(generation, documents(ids), waitForRefresh);
    state.acknowledge(changes.stream().map(Change::id).toList());
  }

  private List<Document> documents(List<Long> ids) {
    var source = readProducts(ids);
    var options = skuSearch.options(ids);
    var localized = new HashMap<Long, Map<String, ProductTranslation>>();
    for (var row : translations.selectList(new LambdaQueryWrapper<ProductTranslation>()
      .in(ProductTranslation::getProductId, ids))) {
      localized.computeIfAbsent(row.getProductId(), key -> new HashMap<>()).put(row.getLocale(), row);
    }

    List<Document> documents = new ArrayList<>();
    for (var id : ids) {
      var product = source.get(id);
      if (product == null || !"ACTIVE".equals(product.getLifecycleStatus())) {
        documents.add(new Document(id, 0, 0, false, "", "", "", "", "", "", Map.of(), List.of()));
      } else {
        var variants = localized.getOrDefault(id, Map.of());
        var zh = CatalogProductV1.from(product, variants.get("zh-CN"));
        var en = CatalogProductV1.from(product, variants.get("en"));
        String zhText = fullText(zh);
        String enText = fullText(en);
        long cents = CatalogElasticsearchClient.cents(product.getPrice());
        var sellable = new ArrayList<Option>();
        sellable.add(new Option(cents, List.of()));
        sellable.addAll(options.getOrDefault(id, List.of()));
        documents.add(new Document(id, product.getMerchantId(), CatalogElasticsearchClient.cents(product.getPrice()),
          true, zhText, enText, CatalogElasticsearchClient.normalize(zh.name()),
          CatalogElasticsearchClient.normalize(en.name()), zhText, enText, Map.of("zh-CN", zh, "en", en), sellable));
      }
    }
    return documents;
  }

  private String fullText(CatalogProductV1 item) {
    return CatalogElasticsearchClient.normalize(String.join(" ", item.name(), item.brief(), item.description()));
  }

  public IndexStatus status(CatalogSearchMapper.State catalog) {
    var indexed = state.state();
    var backlog = state.backlog();
    String phase;
    try {
      if (synchronizationFailed) {
        phase = "UNAVAILABLE";
      } else if (catalog.rebuildRequested() || indexed.catalogGeneration() != catalog.generation()) {
        phase = "BUILDING";
      } else if (indexed.generation() == 0) {
        phase = "INITIALIZING";
      } else if (!client.exists(indexed.generation())) {
        phase = "UNAVAILABLE";
      } else {
        phase = backlog.pending() == 0 ? "READY" : "CATCHING_UP";
      }
    } catch (RestClientException unavailable) {
      phase = "UNAVAILABLE";
    }
    return new IndexStatus(phase, indexed.generation(), backlog.pending(), backlog.oldestAt());
  }

  public Comparison compare(long afterId, Long generation) {
    int size = CatalogElasticsearchMapper.PAGE_SIZE;
    var indexed = state.state();
    long current = indexed.generation();
    if (generation != null && generation != current) return new Comparison("RESTART", current, 0, null, List.of());
    if (current == 0) return new Comparison("INITIALIZING", current, 0, null, List.of());
    try {
      var ids = state.productIdsAfter(afterId);
      var actual = client.documentsAfter(current, afterId, size);
      long boundary = Math.min(ids.size() == size ? ids.getLast() : Long.MAX_VALUE,
        actual.size() == size ? actual.getLast().productId() : Long.MAX_VALUE);
      var expected = ids.isEmpty() ? Map.<Long, Document>of()
        : documents(ids).stream().collect(Collectors.toMap(Document::productId, Function.identity()));
      var byId = actual.stream().collect(Collectors.groupingBy(IndexedDocument::productId));
      var keys = new TreeSet<>(expected.keySet());
      keys.addAll(byId.keySet());
      var mismatches = new ArrayList<Difference>();
      int checked = 0;
      for (long id : keys.headSet(boundary, true)) {
        var source = expected.get(id);
        var hits = byId.getOrDefault(id, List.of());
        String reason = difference(source, hits);
        if (reason != null) {
          var product = source == null ? null : source.products().get("zh-CN");
          mismatches.add(new Difference(id, reason, product == null ? null : product.businessVersion(),
            hits.isEmpty() ? null : hits.getFirst().businessVersion()));
        }
        checked++;
      }
      long latest = state.state().generation();
      if (latest != current) {
        return new Comparison("RESTART", latest, 0, null, List.of());
      }
      String status = state.hasPending() ? "CATCHING_UP" : "CHECKED";
      return new Comparison(status, current, checked, boundary == Long.MAX_VALUE ? null : boundary, mismatches);
    } catch (RestClientException | JacksonException unavailable) {
      return new Comparison("UNAVAILABLE", current, 0, null, List.of());
    }
  }

  private String difference(Document expected, List<IndexedDocument> indexed) {
    if (expected == null) return "EXTRA";
    if (indexed.isEmpty()) return "MISSING";
    if (indexed.size() != 1 || !client.matches(indexed.getFirst(), expected)) return "DIFFERENT";
    return null;
  }

  public record IndexStatus(String phase, long generation, long pending, Instant oldestAt) {
  }

  public record Comparison(String status, long generation, int checked, Long nextAfterId,
                           List<Difference> differences) {
  }

  public record Difference(long productId, String reason, Long expectedVersion, Long indexedVersion) {
  }

  public Optional<Page> search(Criteria criteria, int requestedPage) {
    var indexed = state.state();
    var catalog = search.state();
    if (indexed.generation() > 0 && indexed.catalogGeneration() == catalog.generation()
        && !catalog.rebuildRequested() && !state.hasPending()) {
      try {
        var result = client.search(indexed.generation(), criteria, requestedPage);
        if (result.isPresent() && matchingVersions(result.get().products())) {
          reads.increment();
          return result;
        }
      } catch (RestClientException | JacksonException | ArithmeticException failure) {
        LOG.debug("Using MySQL for this catalog search", failure);
      }
    }
    fallbacks.increment();
    return Optional.empty();
  }

  private boolean matchingVersions(List<CatalogProductV1> hits) {
    if (hits.isEmpty()) return true;
    var current = readProducts(hits.stream().map(CatalogProductV1::productId).toList());
    return hits.stream().allMatch(hit -> {
      var product = current.get(hit.productId());
      return product != null && "ACTIVE".equals(product.getLifecycleStatus())
        && product.getBusinessVersion() == hit.businessVersion();
    });
  }

  private Map<Long, Product> readProducts(List<Long> ids) {
    var byId = new HashMap<Long, Product>();
    for (var product : products.selectList(new LambdaQueryWrapper<Product>().in(Product::getId, ids))) {
      byId.put(product.getId(), product);
    }
    return byId;
  }
}
