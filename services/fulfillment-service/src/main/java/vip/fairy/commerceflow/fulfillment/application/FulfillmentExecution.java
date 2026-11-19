package vip.fairy.commerceflow.fulfillment.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Comparator;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.fulfillment.application.ShadowReplication.CommandStatus;
import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;
import vip.fairy.commerceflow.fulfillment.domain.FulfillmentCommand;
import vip.fairy.commerceflow.fulfillment.domain.FulfillmentCommand.Operation;
import vip.fairy.commerceflow.fulfillment.infrastructure.ExecutionMapper;
import vip.fairy.commerceflow.fulfillment.infrastructure.FulfillmentMode;
import vip.fairy.commerceflow.fulfillment.infrastructure.ShadowDatabases;

@Service
public class FulfillmentExecution {

  private final FulfillmentMode mode;
  private final ShadowDatabases databases;
  private final FulfillmentDownstream downstream;
  private final ObjectMapper mapper;

  public FulfillmentExecution(FulfillmentMode mode, ShadowDatabases databases, FulfillmentDownstream downstream,
                              ObjectMapper mapper) {
    this.mode = mode;
    this.databases = databases;
    this.downstream = downstream;
    this.mapper = mapper;
  }

  public CommandStatus accept(FulfillmentCommand command, boolean legacy) {
    if (!mode.execution()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "Fulfillment business writes are closed");
    command.validate();
    String id = command.commandId().toString();
    ExecutionTask prior = find(id);
    if (prior != null) return replay(command, prior);

    FulfillmentDownstream.OrderFacts facts;
    try {
      facts = downstream.order(command.merchantId(), command.orderId());
    } catch (RuntimeException failure) {
      throw error(HttpStatus.SERVICE_UNAVAILABLE, "Order ownership cannot be verified");
    }
    if (facts != null && command.operation() == Operation.SHIP && command.lines() == null
        && shipmentLines(command).isEmpty()) {
      throw error(HttpStatus.CONFLICT, "Shipment requires all order lines to belong to the merchant");
    }

    try (var session = databases.target()) {
      ExecutionMapper store = session.getMapper(ExecutionMapper.class);
      store.lockOrder(command.orderId());
      prior = store.find(id);
      if (prior != null) return replay(command, prior);
      if (legacy) {
        String slot = store.legacySlot(command);
        if (slot != null) return replay(command, store.find(slot));
      }
      ExecutionTask task = prepare(command, facts);
      if (store.hasOpenTask(task.orderId()) || store.businessSlotOccupied(task)) {
        throw error(HttpStatus.CONFLICT, "Fulfillment business slot is occupied");
      }
      store.reserveCommand(id, task.orderId());
      if (!command.orderId().equals(store.commandOrder(id))) {
        throw error(HttpStatus.CONFLICT, "Fulfillment command ID was already used");
      }
      store.insert(task);
      session.commit();
      return status(task);
    }
  }

  private List<FulfillmentDownstream.ShipmentLine> shipmentLines(FulfillmentCommand command) {
    try {
      return downstream.shipmentLines(command.merchantId(), command.orderId());
    } catch (RuntimeException failure) {
      throw error(HttpStatus.SERVICE_UNAVAILABLE, "Order shipment lines cannot be verified");
    }
  }

  public CommandStatus query(UUID id, long merchantId) {
    ExecutionTask task = find(id.toString());
    return task != null && task.merchantId() == merchantId ? status(task) : null;
  }

  public ExecutionTask shipment(long orderId, long merchantId) {
    if (!mode.execution()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "Fulfillment business reads are closed");
    try (var session = databases.target()) {
      ExecutionTask shipment = session.getMapper(ExecutionMapper.class).shipment(orderId);
      if (shipment == null || shipment.merchantId() != merchantId) {
        throw error(HttpStatus.NOT_FOUND, "Shipment operation not found");
      }
      return shipment;
    }
  }

  private ExecutionTask prepare(FulfillmentCommand command, FulfillmentDownstream.OrderFacts order) {
    if (order == null) throw error(HttpStatus.NOT_FOUND, "Merchant order not found");
    Operation operation = command.operation();
    String expectedState = switch (operation) {
      case CONFIRM -> "PAID";
      case SHIP -> "CONFIRMED";
      case UPDATE_SHIPMENT -> "SHIPPED";
    };
    if (!expectedState.equals(order.status())
        || (operation == Operation.SHIP && !"COMMITTED".equals(order.inventoryStatus()))
        || (operation == Operation.UPDATE_SHIPMENT && order.shipmentVersion() != command.expectedShipmentVersion())) {
      throw error(HttpStatus.CONFLICT, "Order facts do not authorize this fulfillment operation");
    }
    String detail = null;
    if (operation == Operation.UPDATE_SHIPMENT) {
      if (Objects.equals(order.carrier(), command.carrier())
          && Objects.equals(order.trackingNumber(), command.trackingNumber())) {
        throw error(HttpStatus.CONFLICT, "Shipment details are unchanged");
      }
      detail = order.carrier() + " / " + order.trackingNumber() + " -> "
        + command.carrier() + " / " + command.trackingNumber();
    }
    String step = switch (operation) {
      case CONFIRM -> "ORDER";
      case SHIP -> "RESERVE";
      case UPDATE_SHIPMENT -> "CHANNEL";
    };
    return new ExecutionTask(command.commandId().toString(), command.orderId(), command.merchantId(), operation,
      operation == Operation.SHIP ? order.orderNumber() : null, command.carrier(), command.trackingNumber(),
      command.expectedShipmentVersion(), detail, step, "PENDING", null, packageLines(command), command.warehouseId());
  }

  @Scheduled(fixedDelayString = "${commerceflow.fulfillment.execution-delay:1s}")
  public void run() {
    if (!mode.execution()) return;
    List<String> ids;
    try (var session = databases.target()) {
      ids = session.getMapper(ExecutionMapper.class).due();
    }
    for (String id : ids) runTask(id);
  }

  private void runTask(String id) {
    String owner = UUID.randomUUID().toString();
    ExecutionTask task;
    boolean strictReservation;
    try (var session = databases.target()) {
      ExecutionMapper store = session.getMapper(ExecutionMapper.class);
      task = store.find(id);
      if (task == null || store.claim(task, owner) != 1) return;
      strictReservation = task.shipment() && "RESERVE".equals(task.step())
        && store.hasRejectedReservation(task.orderId());
      session.commit();
    }
    StepResult outcome;
    try {
      outcome = step(task, strictReservation);
    } catch (RuntimeException failure) {
      try (var session = databases.target()) {
        session.getMapper(ExecutionMapper.class).retry(task, owner, task.step() + "_ATTEMPT_FAILED");
        session.commit();
      }
      return;
    }
    try (var session = databases.target()) {
      ExecutionMapper store = session.getMapper(ExecutionMapper.class);
      if (outcome.nextStep() != null) store.advance(task, owner, outcome.nextStep());
      else store.finish(task, owner, outcome.status(), outcome.errorCode());
      session.commit();
    }
  }

  private StepResult step(ExecutionTask task, boolean strictReservation) {
    String id = task.commandId();
    String stepId = UUID.nameUUIDFromBytes((id + ":" + task.step()).getBytes(StandardCharsets.UTF_8)).toString();
    return switch (task.step()) {
      case "RESERVE" -> {
        if (task.packageShipment()) {
          yield downstream.reservePackage(task).accepted() ? new StepResult("LOGISTICS", null, null)
            : new StepResult(null, "REJECTED", "PACKAGE_ADMISSION_REJECTED");
        }
        if (strictReservation && downstream.hasShipment(task.orderId())) {
          yield new StepResult(null, "REVIEW_REQUIRED", "EXISTING_LOGISTICS_EVIDENCE");
        }
        String type = strictReservation ? "RESERVE_NEW_SHIPMENT" : "RESERVE_SHIPMENT";
        yield downstream.mutateOrder(stepId, type, task) ? new StepResult("LOGISTICS", null, null)
          : new StepResult(null, "REJECTED", "ORDER_SHIPMENT_ADMISSION_REJECTED");
      }
      case "LOGISTICS" -> {
        postOutbound(task);
        downstream.writeLogistics(stepId, "ENSURE_SHIPMENT", task);
        yield new StepResult("FINISH_ORDER", null, null);
      }
      case "CHANNEL" -> {
        downstream.writeLogistics(stepId, "UPDATE_DETAILS", task);
        yield new StepResult("ORDER", null, null);
      }
      case "FINISH_ORDER" -> {
        postOutbound(task);
        boolean finished = task.packageShipment() ? downstream.finishPackage(task)
          : downstream.mutateOrder(stepId, "MERCHANT_SHIP", task);
        yield finished
          ? completed() : new StepResult(null, "REVIEW_REQUIRED", "ORDER_SHIPMENT_COMPLETION_REJECTED");
      }
      case "ORDER" -> {
        boolean confirm = task.operation() == Operation.CONFIRM;
        yield downstream.mutateOrder(id, confirm ? "MERCHANT_CONFIRM" : "UPDATE_SHIPMENT", task) ? completed()
          : new StepResult(null, confirm ? "REJECTED" : "REVIEW_REQUIRED", "ORDER_MUTATION_REJECTED");
      }
      default -> throw new IllegalStateException("Unknown fulfillment step");
    };
  }

  private void postOutbound(ExecutionTask task) {
    var lines = task.packageShipment() ? downstream.reservePackage(task).lines()
      : downstream.shipmentLines(task.merchantId(), task.orderId());
    if (lines.isEmpty()) throw new IllegalStateException("Order shipment lines are unavailable");
    downstream.postOutbound(task, lines);
  }

  private StepResult completed() {
    return new StepResult(null, "COMPLETED", null);
  }

  private ExecutionTask find(String id) {
    try (var session = databases.target()) {
      return session.getMapper(ExecutionMapper.class).find(id);
    }
  }

  private CommandStatus replay(FulfillmentCommand command, ExecutionTask prior) {
    if (command.merchantId() != prior.merchantId()) throw error(HttpStatus.NOT_FOUND, "Command not found");
    if (command.orderId() != prior.orderId() || command.operation() != prior.operation()
        || !Objects.equals(command.carrier(), prior.carrier())
        || !Objects.equals(command.trackingNumber(), prior.trackingNumber())
        || !Objects.equals(command.expectedShipmentVersion(), prior.expectedVersion())
        || !Objects.equals(command.warehouseId(), prior.warehouseId())
        || !Objects.equals(packageLines(command), prior.packageLines())) {
      throw error(HttpStatus.CONFLICT, "Fulfillment command ID was reused with different parameters");
    }
    return status(prior);
  }

  private String packageLines(FulfillmentCommand command) {
    if (command.lines() == null) return null;
    return mapper.writeValueAsString(command.lines().stream()
      .sorted(Comparator.comparingLong(FulfillmentCommand.PackageLine::orderLineId)).toList());
  }

  private CommandStatus status(ExecutionTask task) {
    Long version = null;
    if ("COMPLETED".equals(task.status())) {
      if (task.shipment()) version = 0L;
      if (task.operation() == Operation.UPDATE_SHIPMENT) version = task.expectedVersion() + 1;
    }
    return new CommandStatus(1, UUID.fromString(task.commandId()), task.orderId(), task.operation().name(),
      task.step(), task.status(), version, task.errorCode(), false);
  }

  private ResponseStatusException error(HttpStatus status, String reason) {
    return new ResponseStatusException(status, reason);
  }

  private record StepResult(String nextStep, String status, String errorCode) {
  }
}
