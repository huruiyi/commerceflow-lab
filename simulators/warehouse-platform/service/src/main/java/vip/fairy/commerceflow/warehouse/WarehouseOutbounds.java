package vip.fairy.commerceflow.warehouse;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class WarehouseOutbounds {

  private final WarehouseOutboundMapper store;
  private final ObjectMapper mapper;

  WarehouseOutbounds(WarehouseOutboundMapper store, ObjectMapper mapper) {
    this.store = store;
    this.mapper = mapper;
  }

  @Transactional
  public Receipt submit(Command command) {
    validateId(command.outboundId());
    if (command.orderId() <= 0 || command.merchantId() <= 0 || command.warehouseId() <= 0
        || command.lines() == null || command.lines().isEmpty() || command.lines().size() > 200
        || command.lines().stream().anyMatch(line -> line == null || line.orderLineId() <= 0
          || line.productId() <= 0 || line.quantity() <= 0)
        || command.lines().stream().map(PlanLine::orderLineId).distinct().count() != command.lines().size()) {
      throw invalid("Invalid outbound plan");
    }
    var planned = command.lines().stream().sorted(Comparator.comparingLong(PlanLine::orderLineId)).toList();
    store.insert(command, mapper.writeValueAsString(planned));
    var row = store.lock(command.outboundId());
    var receipt = receipt(row);
    if (row.orderId() != command.orderId() || row.merchantId() != command.merchantId()
        || row.warehouseId() != command.warehouseId() || row.wholeOrder() != command.wholeOrder()
        || !planned.equals(receipt.planned())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Outbound ID already has a different plan");
    }
    if (row.wholeOrder() && "PENDING".equals(row.status())) {
      // 整单发货是全量作业，由模拟仓自动确认。显式包裹交由仓库后台登记实发件数。
      var full = planned.stream().map(line -> new Count(line.orderLineId(), line.quantity())).toList();
      return decide(row, full);
    }
    return receipt;
  }

  public Receipt get(String id) {
    validateId(id);
    return receipt(required(store.get(id)));
  }

  public List<Receipt> list(Long orderId) {
    return store.list(orderId).stream().map(this::receipt).toList();
  }

  @Transactional
  public Receipt record(String id, Decision command) {
    validateId(id);
    var row = required(store.lock(id));
    if (row.warehouseId() != command.warehouseId() || command.lines() == null
        || command.lines().stream().anyMatch(java.util.Objects::isNull)) {
      throw invalid("Warehouse and actual counts must match the outbound");
    }
    var actual = command.lines().stream().sorted(Comparator.comparingLong(Count::orderLineId)).toList();
    var previous = receipt(row);
    var planned = previous.planned();
    if (planned.size() != actual.size()) throw invalid("Every planned line requires a count");
    for (int index = 0; index < planned.size(); index++) {
      var plan = planned.get(index);
      var count = actual.get(index);
      if (count.orderLineId() != plan.orderLineId() || count.quantity() < 0 || count.quantity() > plan.quantity()) {
        throw invalid("Warehouse count exceeds the planned quantity or names a different line");
      }
    }
    if (!"PENDING".equals(row.status())) {
      if (actual.equals(previous.actual())) return previous;
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Warehouse decision is already final");
    }
    return decide(row, actual);
  }

  private Receipt decide(WarehouseOutboundMapper.Row row, List<Count> actual) {
    String status = actual.stream().anyMatch(line -> line.quantity() > 0) ? "DISPATCHED" : "VOIDED";
    if (store.decide(row.outboundId(), status, mapper.writeValueAsString(actual)) != 1) {
      throw new IllegalStateException("Could not persist warehouse decision");
    }
    return receipt(store.get(row.outboundId()));
  }

  private Receipt receipt(WarehouseOutboundMapper.Row row) {
    List<PlanLine> planned = mapper.readValue(row.planned(), new TypeReference<>() { });
    List<Count> actual = row.actual() == null ? null : mapper.readValue(row.actual(), new TypeReference<>() { });
    return new Receipt(row.outboundId(), row.orderId(), row.merchantId(), row.warehouseId(), row.status(),
      planned, actual, row.createdAt(), row.decidedAt());
  }

  private WarehouseOutboundMapper.Row required(WarehouseOutboundMapper.Row row) {
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Outbound not found");
    return row;
  }

  private void validateId(String id) {
    if (id == null) throw invalid("Outbound ID is required");
    try {
      if (!UUID.fromString(id).toString().equals(id)) throw invalid("Outbound ID is not canonical");
    } catch (IllegalArgumentException error) {
      throw invalid("Outbound ID is not canonical");
    }
  }

  private ResponseStatusException invalid(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  public record Command(String outboundId, long orderId, long merchantId, long warehouseId, boolean wholeOrder,
                        List<PlanLine> lines) {
  }

  public record Decision(long warehouseId, List<Count> lines) {
  }

  public record PlanLine(long orderLineId, long productId, int quantity) {
  }

  public record Count(long orderLineId, int quantity) {
  }

  public record Receipt(String outboundId, long orderId, long merchantId, long warehouseId, String status,
                        List<PlanLine> planned, List<Count> actual, Instant createdAt, Instant decidedAt) {
  }
}
