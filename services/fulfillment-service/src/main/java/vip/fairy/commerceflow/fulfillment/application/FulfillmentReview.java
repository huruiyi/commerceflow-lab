package vip.fairy.commerceflow.fulfillment.application;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;
import vip.fairy.commerceflow.fulfillment.domain.RecoveryRecord;
import vip.fairy.commerceflow.fulfillment.infrastructure.ExecutionMapper;
import vip.fairy.commerceflow.fulfillment.infrastructure.FulfillmentMode;
import vip.fairy.commerceflow.fulfillment.infrastructure.ShadowDatabases;

@Service
public class FulfillmentReview {

  private final ShadowDatabases databases;
  private final FulfillmentMode mode;
  private final FulfillmentDownstream downstream;

  public FulfillmentReview(ShadowDatabases databases, FulfillmentMode mode, FulfillmentDownstream downstream) {
    this.databases = databases;
    this.mode = mode;
    this.downstream = downstream;
  }

  public List<ExecutionTask> list(int limit, Boolean shipment) {
    requireExecution();
    try (var session = databases.target()) {
      ExecutionMapper store = session.getMapper(ExecutionMapper.class);
      return store.reviewable(Math.clamp(limit, 1, 100), shipment).stream().map(store::find).toList();
    }
  }

  public Review detail(UUID id) {
    requireExecution();
    try (var session = databases.target()) {
      ExecutionMapper store = session.getMapper(ExecutionMapper.class);
      ExecutionTask task = requireTask(store.find(id.toString()));
      return new Review(task, store.recoveryHistory(task));
    }
  }

  public Review shipment(long orderId) {
    requireExecution();
    try (var session = databases.target()) {
      ExecutionMapper store = session.getMapper(ExecutionMapper.class);
      return new Review(requireTask(store.shipment(orderId)), store.shipmentRecoveryHistory(orderId));
    }
  }

  public Review merchantOperation(UUID id) {
    Review result = detail(id);
    requireScope(result.operation(), null, true);
    return result;
  }

  public RecoveryRecord retry(UUID id, UUID recoveryId, long operatorId, String reason, String evidence,
                              Long orderId, boolean merchantOperation) {
    requireExecution();
    String normalizedReason = requiredText(reason, 300);
    String normalizedEvidence = requiredText(evidence, 500);
    if (recoveryId == null || operatorId <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recovery ID and operator are required");
    }
    ExecutionTask routing;
    try (var session = databases.target()) {
      routing = requireTask(session.getMapper(ExecutionMapper.class).find(id.toString()));
    }
    try (var session = databases.target()) {
      ExecutionMapper store = session.getMapper(ExecutionMapper.class);
      if (store.lockTask(routing) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
      ExecutionTask task = requireTask(store.find(id.toString()));
      requireScope(task, orderId, merchantOperation);
      String requestId = recoveryId.toString();
      RecoveryRecord recorded = store.recovery(task, requestId);
      if (recorded != null) {
        if (!recorded.commandId().equals(task.commandId()) || recorded.operatorId() != operatorId
            || !recorded.reason().equals(normalizedReason) || !recorded.evidence().equals(normalizedEvidence)) {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "Recovery ID was reused with different details");
        }
        return recorded;
      }
      if (!"REVIEW_REQUIRED".equals(task.status())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Task is not available for reconciliation");
      }
      if (task.shipment() && !"RESERVE".equals(task.step()) && !downstream.hasMatchingWarehouseDispatch(task)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Matching warehouse dispatch receipt is required");
      }
      if (store.requeue(task) != 1) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Task is not available for reconciliation");
      }
      store.insertRecovery(task, new RecoveryRecord(task.commandId(), requestId, operatorId, task.step(),
        task.errorCode(), normalizedReason, normalizedEvidence, null));
      RecoveryRecord result = store.recovery(task, requestId);
      session.commit();
      return result;
    }
  }

  private void requireScope(ExecutionTask task, Long orderId, boolean merchantOperation) {
    if ((merchantOperation && task.shipment()) || (orderId != null && !task.shipment())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
    }
    if (orderId != null && task.orderId() != orderId) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Recovery does not match the shipment operation");
    }
  }

  private void requireExecution() {
    if (!mode.execution()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Fulfillment recovery is closed");
    }
  }

  private ExecutionTask requireTask(ExecutionTask task) {
    if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
    return task;
  }

  private String requiredText(String value, int size) {
    if (value == null || value.isBlank() || value.strip().length() > size) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recovery reason and evidence are required");
    }
    return value.strip();
  }

  public record Review(ExecutionTask operation, List<RecoveryRecord> history) {
  }
}
