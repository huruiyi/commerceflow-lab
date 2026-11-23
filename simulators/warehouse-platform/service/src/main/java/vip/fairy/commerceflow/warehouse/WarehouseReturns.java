package vip.fairy.commerceflow.warehouse;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WarehouseReturns {

  private final WarehouseReturnMapper store;

  WarehouseReturns(WarehouseReturnMapper store) {
    this.store = store;
  }

  @Transactional
  public Receipt submit(Command value) {
    if (!canonical(value.returnId()) || !canonical(value.outboundId()) || value.orderId() <= 0
        || value.orderLineId() <= 0 || value.warehouseId() <= 0 || value.expectedQuantity() <= 0
        || value.trackingNumber() == null || value.trackingNumber().isBlank()
        || value.trackingNumber().length() > 120) {
      throw invalid();
    }
    store.insert(value);
    var result = required(value.returnId());
    if (!value.equals(new Command(result.returnId(), result.outboundId(), result.orderId(), result.orderLineId(),
        result.warehouseId(), result.trackingNumber(), result.expectedQuantity()))) throw conflict();
    return result;
  }

  @Transactional(readOnly = true)
  public WarehouseJobSearch.Page<Receipt> search(WarehouseJobSearch search) {
    search.validateStatus(Set.of("WAITING_RECEIPT", "RECEIVED", "PASSED", "FAILED", "SHORT_RECEIVED", "REJECTED"));
    return search.slice(store.count(search), offset -> store.search(search, offset));
  }

  @Transactional
  public Receipt receive(String id, long warehouseId, Integer quantity, Boolean rejected) {
    var prior = required(id);
    if (prior.warehouseId() != warehouseId || quantity == null || quantity < 0 || quantity > prior.expectedQuantity()
        || rejected == null || (rejected && quantity != 0)) throw invalid();
    String result = rejected ? "REJECTED" : quantity < prior.expectedQuantity() ? "SHORT_RECEIVED" : "RECEIVED";
    if (!"WAITING_RECEIPT".equals(prior.status())) {
      boolean same = result.equals(prior.status()) || ("RECEIVED".equals(result) && prior.inspectedAt() != null);
      if (same && Objects.equals(prior.receivedQuantity(), quantity)) return prior;
      throw conflict();
    }
    if (store.receive(id, quantity, result) != 1) throw conflict();
    return required(id);
  }

  @Transactional
  public Receipt inspect(String id, long warehouseId, Boolean passed) {
    var prior = required(id);
    if (prior.warehouseId() != warehouseId || passed == null) throw invalid();
    String result = passed ? "PASSED" : "FAILED";
    if (result.equals(prior.status())) return prior;
    if (!"RECEIVED".equals(prior.status()) || store.inspect(id, result) != 1) throw conflict();
    return required(id);
  }

  @Transactional
  public Receipt supplement(String id, long warehouseId, int quantity, String note) {
    var prior = required(id);
    if (warehouseId != prior.warehouseId() || quantity <= 0 || note == null || note.isBlank()
        || note.length() > 300 || prior.receivedQuantity() == null || quantity > prior.receivedQuantity()) {
      throw invalid();
    }
    if (prior.supplementId() != null) {
      if (Objects.equals(prior.qualifiedQuantity(), quantity) && note.equals(prior.supplementNote())) return prior;
      throw conflict();
    }
    if (store.supplement(id, UUID.randomUUID().toString(), quantity, note) != 1) throw conflict();
    return required(id);
  }

  private Receipt required(String id) {
    if (!canonical(id)) throw invalid();
    var value = store.lock(id);
    if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Warehouse return not found");
    return value;
  }

  private boolean canonical(String id) {
    try {
      return id != null && UUID.fromString(id).toString().equals(id);
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Warehouse, return identity or quantity is invalid");
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Warehouse return state or immutable facts conflict");
  }

  List<Receipt> evidence(long merchantId, List<Long> orderIds) {
    return store.evidence(merchantId, orderIds);
  }

  public record Command(String returnId, String outboundId, long orderId, long orderLineId, long warehouseId,
                        String trackingNumber, int expectedQuantity) {
  }

  public record Receipt(String returnId, String outboundId, long orderId, long orderLineId, long warehouseId,
                        String trackingNumber, int expectedQuantity, Integer receivedQuantity, String status,
                        Instant receivedAt, Instant inspectedAt, String supplementId, Integer qualifiedQuantity,
                        String supplementNote, Instant supplementAt) {
  }
}
