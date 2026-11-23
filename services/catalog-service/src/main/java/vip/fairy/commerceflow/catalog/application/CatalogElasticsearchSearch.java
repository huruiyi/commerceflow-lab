package vip.fairy.commerceflow.catalog.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import vip.fairy.commerceflow.catalog.domain.Product;
import vip.fairy.commerceflow.catalog.domain.ProductTranslation;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogElasticsearchMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSearchMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSearchMapper.Change;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.ProductMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.ProductTranslationMapper;
import vip.fairy.commerceflow.catalog.infrastructure.search.CatalogElasticsearchClient;
import vip.fairy.commerceflow.catalog.infrastructure.search.CatalogElasticsearchClient.Document;

@Service
@ConditionalOnProperty(name = "commerceflow.catalog-search.elasticsearch.enabled", havingValue = "true")
public class CatalogElasticsearchSearch {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogElasticsearchSearch.class);

  private final CatalogElasticsearchClient client;
  private final CatalogElasticsearchMapper state;
  private final CatalogSearchMapper search;
  private final ProductMapper products;
  private final ProductTranslationMapper translations;
  private final TransactionTemplate transactions;
  private final Counter reads;
  private final Counter fallbacks;
  private boolean synchronizationFailed;

  public CatalogElasticsearchSearch(CatalogElasticsearchClient client, CatalogElasticsearchMapper state,
                                    CatalogSearchMapper search, ProductMapper products,
                                    ProductTranslationMapper translations,
                                    PlatformTransactionManager transactionManager, MeterRegistry metrics) {
    this.client = client;
    this.state = state;
    this.search = search;
    this.products = products;
    this.translations = translations;
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
    var localized = new HashMap<Long, Map<String, ProductTranslation>>();
    for (var row : translations.selectList(new LambdaQueryWrapper<ProductTranslation>()
      .in(ProductTranslation::getProductId, ids))) {
      localized.computeIfAbsent(row.getProductId(), key -> new HashMap<>()).put(row.getLocale(), row);
    }

    List<Document> documents = new ArrayList<>();
    for (var id : ids) {
      var product = source.get(id);
      if (product == null || !"ACTIVE".equals(product.getLifecycleStatus())) {
        documents.add(new Document(id, 0, 0, false, "", "", "", "", "", "", Map.of()));
      } else {
        var variants = localized.getOrDefault(id, Map.of());
        var zh = CatalogProductV1.from(product, variants.get("zh-CN"));
        var en = CatalogProductV1.from(product, variants.get("en"));
        String zhText = fullText(zh);
        String enText = fullText(en);
        documents.add(new Document(id, product.getMerchantId(), CatalogElasticsearchClient.cents(product.getPrice()),
          true, zhText, enText, CatalogElasticsearchClient.normalize(zh.name()),
          CatalogElasticsearchClient.normalize(en.name()), zhText, enText, Map.of("zh-CN", zh, "en", en)));
      }
    }
    return documents;
  }

  private String fullText(CatalogProductV1 item) {
    return CatalogElasticsearchClient.normalize(String.join(" ", item.name(), item.brief(), item.description()));
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
