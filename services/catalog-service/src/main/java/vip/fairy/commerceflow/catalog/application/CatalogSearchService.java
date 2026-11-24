package vip.fairy.commerceflow.catalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.catalog.api.CatalogProductV1;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogSearchMapper;

@Service
public class CatalogSearchService {

  private final CatalogSearchMapper store;
  private final ObjectProvider<CatalogElasticsearchSearch> elasticsearch;
  private final boolean indexingEnabled;

  public CatalogSearchService(CatalogSearchMapper store, ObjectProvider<CatalogElasticsearchSearch> elasticsearch,
                              @Value("${commerceflow.catalog-search.indexing-enabled:true}") boolean indexingEnabled) {
    this.store = store;
    this.elasticsearch = elasticsearch;
    this.indexingEnabled = indexingEnabled;
  }

  @Scheduled(
    initialDelayString = "${commerceflow.catalog-search.interval:2s}",
    fixedDelayString = "${commerceflow.catalog-search.interval:2s}"
  )
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void consume() {
    if (!indexingEnabled) return;
    var state = store.lockState();
    if (state.rebuildRequested()) {
      long next = state.generation() + 1;
      store.copyDocuments(next, null);
      long boundary = store.eventBoundary();
      for (var changes = store.pending(boundary); !changes.isEmpty(); changes = store.pending(boundary)) {
        apply(next, changes);
      }
      store.switchGeneration(next);
      store.dropExpired(next);
      return;
    }
    var changes = store.pending(Long.MAX_VALUE);
    if (!changes.isEmpty()) apply(state.generation(), changes);
  }

  private void apply(long generation, List<CatalogSearchMapper.Change> changes) {
    List<Long> products = changes.stream().map(CatalogSearchMapper.Change::productId).distinct().toList();
    store.deleteDocuments(generation, products);
    store.copyDocuments(generation, products);
    store.acknowledge(changes.stream().map(CatalogSearchMapper.Change::id).toList());
  }

  @Transactional
  public Status rebuild() {
    store.requestRebuild();
    return status();
  }

  public Status status() {
    var state = store.state();
    var backend = elasticsearch.getIfAvailable();
    var status = backend == null ? new CatalogElasticsearchSearch.IndexStatus("DISABLED", 0, 0, null)
      : backend.status(state);
    return new Status(state.generation(), state.rebuildRequested(), state.rebuiltAt(), store.pendingCount(), status);
  }

  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public CatalogElasticsearchSearch.Comparison compare(long afterId, Long generation) {
    if (afterId < 0 || (afterId > 0 && generation == null) || (generation != null && generation <= 0)) {
      throw new IllegalArgumentException("Invalid index comparison cursor");
    }
    var backend = elasticsearch.getIfAvailable();
    return backend == null ? new CatalogElasticsearchSearch.Comparison("DISABLED", 0, 0, null, List.of())
      : backend.compare(afterId, generation);
  }

  @Transactional(readOnly = true)
  public Page search(Request request) {
    String text = request.query() == null ? "" : request.query().strip();
    if (text.length() > 120 || request.page() < 0 || request.size() < 1 || request.size() > 48
        || request.sort() == null || !Set.of("default", "price-asc", "price-desc", "rating").contains(request.sort())
        || request.merchantIds() == null || request.ratedIds() == null) {
      throw new IllegalArgumentException("Invalid product search parameters");
    }
    List<String> terms = text.isEmpty() ? List.of() : Arrays.stream(text.split("(?U)\\s+")).distinct().toList();
    if (terms.size() > 12 || invalidPrice(request.minPrice()) || invalidPrice(request.maxPrice())
        || (request.minPrice() != null && request.maxPrice() != null
          && request.minPrice().compareTo(request.maxPrice()) > 0)) {
      throw new IllegalArgumentException("Invalid product search terms or price range");
    }
    if (request.merchantIds().isEmpty()) return new Page(List.of(), 0, 0, request.size());
    Criteria criteria = new Criteria(text, terms, CatalogApplicationService.locale(request.locale()), request.sort(),
      request.merchantIds(), request.ratedIds(), request.minPrice(), request.maxPrice(), request.size(), 0);
    var backend = elasticsearch.getIfAvailable();
    if (backend != null) {
      var page = backend.search(criteria, request.page());
      if (page.isPresent()) return page.get();
    }
    long total = store.count(criteria);
    if (total == 0) return new Page(List.of(), 0, 0, request.size());
    int page = (int) Math.min(request.page(), (total - 1) / request.size());
    return new Page(store.search(criteria.atOffset((long) page * request.size())), total, page, request.size());
  }

  private boolean invalidPrice(BigDecimal price) {
    return price != null && (price.signum() < 0 || price.stripTrailingZeros().scale() > 2);
  }

  public record Request(String query, String locale, String sort, List<Long> merchantIds, List<Long> ratedIds,
                        BigDecimal minPrice, BigDecimal maxPrice, int page, int size) {
  }

  public record Criteria(String query, List<String> terms, String locale, String sort,
                         List<Long> merchantIds, List<Long> ratedIds, BigDecimal minPrice, BigDecimal maxPrice,
                         int size, long offset) {

    Criteria atOffset(long value) {
      return new Criteria(query, terms, locale, sort, merchantIds, ratedIds, minPrice, maxPrice, size, value);
    }
  }

  public record Page(List<CatalogProductV1> products, long total, int page, int size) {
  }

  public record Status(long generation, boolean rebuildRequested, Instant rebuiltAt, long pending,
                       CatalogElasticsearchSearch.IndexStatus elasticsearch) {
  }
}
