package vip.fairy.commerceflow.reference.catalog;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Comparison;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Dataset;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Node;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.NodePage;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Publication;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Release;

@Service
class ReferenceCatalog {

  private final ReferenceMapper store;

  ReferenceCatalog(ReferenceMapper store) {
    this.store = store;
  }

  List<Dataset> datasets() {
    return store.datasets();
  }

  Dataset dataset(String key) {
    return require(store.dataset(key), "Dataset not found");
  }

  Release release(String key, long id) {
    return require(store.release(key, id), "Version not found");
  }

  List<Release> releases(String key, Long before) {
    dataset(key);
    return store.releases(key, before);
  }

  List<Publication> publications(String key, Long before) {
    dataset(key);
    return store.publications(key, before);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  Comparison compare(String key, long id) {
    Long current = dataset(key).currentReleaseId();
    release(key, id);
    return new Comparison(current, store.changeCounts(current, id), store.changeSample(current, id));
  }

  @Transactional
  Release publish(String key, long id, Long expectedCurrent, long actor) {
    Long current = require(store.lockDataset(key), "Dataset not found").currentReleaseId();
    Release candidate = release(key, id);
    if (Objects.equals(current, id)) {
      return candidate;
    }
    if (!Objects.equals(current, expectedCurrent)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Published version changed. Compare again.");
    }
    store.markPublished(id);
    store.activate(key, id);
    store.recordPublication(key, id, current, actor);
    return release(key, id);
  }

  Release resolvePublished(String key, Long requested) {
    Long id = requested == null ? dataset(key).currentReleaseId() : requested;
    if (id == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset has no published version");
    }
    Release release = release(key, id);
    if (release.publishedAt() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Version has not been published");
    }
    return release;
  }

  NodePage nodes(String key, long id, String parent, String query, String after, int size, boolean flat) {
    if (size < 1 || size > 500 || length(parent) > 64 || length(query) > 100 || length(after) > 64) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid query or page size");
    }
    List<Node> entries = store.nodes(id, blankToNull(parent), blankToNull(query), blankToNull(after), size + 1, flat);
    List<Node> rows = entries.size() > size ? entries.subList(0, size) : entries;
    String next = entries.size() > size ? rows.getLast().code() : null;
    return new NodePage(key, id, rows, next);
  }

  Node node(long id, String code) {
    return require(store.node(id, code), "Code not found");
  }

  private static int length(String value) {
    return value == null ? 0 : value.length();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static <T> T require(T value, String message) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
    return value;
  }
}
