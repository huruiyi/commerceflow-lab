package vip.fairy.commerceflow.commerce.fulfillment.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentService.CommandResult;
import vip.fairy.commerceflow.commerce.fulfillment.domain.FulfillmentOperation;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import vip.fairy.commerceflow.commerce.fulfillment.application.FulfillmentAdmission.Accepted;
import vip.fairy.commerceflow.commerce.fulfillment.application.contract.FulfillmentCommandV1;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob.Operation;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob.Status;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob.Step;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.MerchantFulfillmentJobMapper;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.ShipmentJobMapper;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Command;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@Service
@ConditionalOnProperty(name = "commerceflow.fulfillment.remote.enabled", havingValue = "false", matchIfMissing = true)
public class FulfillmentWorkflow implements MerchantFulfillmentRoute {

  private final ShipmentJobMapper jobs;
  private final MerchantFulfillmentJobMapper merchantJobs;
  private final FulfillmentAdmission admission;
  private final OrderStorePort orders;
  private final LogisticsFulfillmentV1 logistics;

  public FulfillmentWorkflow(ShipmentJobMapper jobs, MerchantFulfillmentJobMapper merchantJobs,
                             FulfillmentAdmission admission, OrderStorePort orders, LogisticsFulfillmentV1 logistics) {
    this.jobs = jobs;
    this.merchantJobs = merchantJobs;
    this.admission = admission;
    this.orders = orders;
    this.logistics = logistics;
  }

  @Override
  public CommandResult submit(FulfillmentCommandV1 command) {
    Long merchantId = command.merchantId();
    String id = command.commandId().toString();
    FulfillmentOperation prior = admission.find(id);
    if (prior != null) return replay(merchantId, command, prior);
    try {
      var prepared = orders.findMerchant(merchantId, command.orderId())
        .orElseThrow(() -> new ResourceNotFoundException("Merchant order not found"));
      String detail = validateCommand(command, prepared.getStatus(), prepared.getInventoryStatus(),
        prepared.getShipmentVersion(), prepared.getCarrier(), prepared.getTrackingNumber());
      submitCommand(command, prepared.getOrderNumber(), detail);
    } catch (BusinessRuleException conflict) {
      // A concurrent identical submission can commit while the order snapshot is being read.
      prior = admission.find(id);
      if (prior != null) return replay(merchantId, command, prior);
      throw conflict;
    }
    return commandStatus(merchantId, id);
  }

  @Override
  public CommandResult commandStatus(Long merchantId, String commandId) {
    return CommandResult.from(ownedCommand(merchantId, admission.find(commandId)));
  }

  private CommandResult replay(Long merchantId, FulfillmentCommandV1 command, FulfillmentOperation operation) {
    ownedCommand(merchantId, operation);
    if (!operation.orderId().equals(command.orderId()) || !operation.operation().equals(command.operation().name())
        || !Objects.equals(operation.expectedShipmentVersion(), command.expectedShipmentVersion())
        || !Objects.equals(operation.carrier(), command.carrier())
        || !Objects.equals(operation.trackingNumber(), command.trackingNumber())) {
      throw new BusinessRuleException("Fulfillment command ID was reused with different parameters");
    }
    return CommandResult.from(operation);
  }

  private FulfillmentOperation ownedCommand(Long merchantId, FulfillmentOperation operation) {
    if (operation == null || !merchantId.equals(operation.merchantId())) {
      throw new ResourceNotFoundException("Fulfillment operation not found");
    }
    return operation;
  }

  private String validateCommand(FulfillmentCommandV1 command, String status, String inventoryStatus,
                                 long shipmentVersion, String carrier, String trackingNumber) {
    String requiredStatus = switch (command.operation()) {
      case CONFIRM -> "PAID";
      case SHIP -> "CONFIRMED";
      case UPDATE_SHIPMENT -> "SHIPPED";
    };
    if (!requiredStatus.equals(status)) {
      throw new BusinessRuleException("Order state does not allow the fulfillment operation");
    }
    return switch (command.operation()) {
      case CONFIRM -> null;
      case SHIP -> {
        if (!"COMMITTED".equals(inventoryStatus)) {
          throw new BusinessRuleException("Inventory confirmation is required before fulfillment");
        }
        yield null;
      }
      case UPDATE_SHIPMENT -> {
        if (shipmentVersion != command.expectedShipmentVersion().longValue()) {
          throw new BusinessRuleException("Shipment version conflict. Reload the order and try again");
        }
        if (Objects.equals(carrier, command.carrier()) && Objects.equals(trackingNumber, command.trackingNumber())) {
          throw new BusinessRuleException("Shipment details are unchanged");
        }
        yield carrier + " / " + trackingNumber + " -> "
          + command.carrier() + " / " + command.trackingNumber();
      }
    };
  }

  @Override
  public CommandResult legacy(FulfillmentCommandV1 command, String orderNumber, String detail) {
    return switch (command.operation()) {
      case SHIP -> {
        ShipmentJob job = submitShipment(new ShipmentJob(command.orderId(), command.commandId().toString(),
          command.merchantId(), orderNumber, command.carrier(), command.trackingNumber(),
          Step.RESERVE, Status.PENDING, null), false);
        if (job.status() == Status.REJECTED) {
          throw new BusinessRuleException("Shipment conflicts with an existing fulfillment or refund operation");
        }
        if (job.status() == Status.REVIEW_REQUIRED) {
          throw new BusinessRuleException("Shipment requires manual reconciliation");
        }
        yield commandStatus(command.merchantId(), job.commandId());
      }
      case CONFIRM, UPDATE_SHIPMENT -> {
        long expectedVersion = command.expectedShipmentVersion() == null ? 0 : command.expectedShipmentVersion();
        MerchantFulfillmentJob.Step step = command.operation() == FulfillmentCommandV1.Operation.CONFIRM
          ? MerchantFulfillmentJob.Step.ORDER : MerchantFulfillmentJob.Step.CHANNEL;
        MerchantFulfillmentJob job = accept(new MerchantFulfillmentJob(command.commandId().toString(),
          command.orderId(), command.merchantId(), Operation.valueOf(command.operation().name()), expectedVersion,
          command.carrier(), command.trackingNumber(), detail, step, Status.PENDING, null), false);
        yield commandStatus(command.merchantId(), job.commandId());
      }
    };
  }

  @Override
  public ShipmentJob shipment(Long merchantId, Long orderId) {
    return jobs.find(orderId);
  }

  private ShipmentJob submitShipment(ShipmentJob request, boolean explicitCommand) {
    Long orderId = request.orderId();
    if (jobs.hasRejectedReservation(orderId) && jobs.findSlot(orderId) == null && logistics.hasShipment(orderId)) {
      throw new BusinessRuleException("Existing logistics evidence requires reconciliation before a new shipment");
    }
    Accepted<ShipmentJob> accepted = admission.shipment(request, explicitCommand);
    String commandId = accepted.job().commandId();
    execute(commandId, accepted.created());
    return jobs.findCommand(commandId);
  }

  private void submitCommand(FulfillmentCommandV1 command, String orderNumber, String detail) {
    String commandId = command.commandId().toString();
    switch (command.operation()) {
      case SHIP -> submitShipment(new ShipmentJob(command.orderId(), commandId, command.merchantId(),
        orderNumber, command.carrier(), command.trackingNumber(), Step.RESERVE, Status.PENDING, null), true);
      case CONFIRM -> accept(new MerchantFulfillmentJob(commandId, command.orderId(), command.merchantId(),
        Operation.CONFIRM, 0, null, null, null, MerchantFulfillmentJob.Step.ORDER, Status.PENDING, null), true);
      case UPDATE_SHIPMENT -> accept(new MerchantFulfillmentJob(commandId, command.orderId(), command.merchantId(),
        Operation.UPDATE_SHIPMENT, command.expectedShipmentVersion(), command.carrier(), command.trackingNumber(),
        detail, MerchantFulfillmentJob.Step.CHANNEL, Status.PENDING, null), true);
    }
  }

  private MerchantFulfillmentJob accept(MerchantFulfillmentJob request, boolean explicitCommand) {
    Accepted<MerchantFulfillmentJob> accepted = admission.merchant(request, explicitCommand);
    MerchantFulfillmentJob job = accepted.job();
    executeMerchantJob(job.commandId(), accepted.created());
    MerchantFulfillmentJob result = merchantJobs.find(job.commandId());
    if (explicitCommand) return result;
    if (result.status() == Status.REJECTED) {
      throw new BusinessRuleException("Order rejected the fulfillment operation");
    }
    if (result.status() == Status.REVIEW_REQUIRED) {
      throw new BusinessRuleException("Fulfillment operation requires manual reconciliation");
    }
    return result;
  }

  @Scheduled(fixedDelayString = "${commerceflow.fulfillment.merchant-operation-delay:2s}")
  public void resumeMerchantOperations() {
    for (String commandId : merchantJobs.dueCommands()) {
      executeMerchantJob(commandId, false);
    }
  }

  private void executeMerchantJob(String commandId, boolean immediate) {
    String owner = UUID.randomUUID().toString();
    if (merchantJobs.claim(commandId, owner, immediate) != 1) return;
    MerchantFulfillmentJob job = merchantJobs.find(commandId);
    MerchantFulfillmentJob.Step step = job.step();
    try {
      if (step == MerchantFulfillmentJob.Step.CHANNEL) {
        logistics.execute(new Command(stepId(commandId, step.name()), LogisticsFulfillmentV1.Operation.UPDATE_DETAILS,
          job.orderId(), null, job.carrier(), job.trackingNumber(), job.expectedVersion()));
        if (merchantJobs.advanceToOrder(commandId, owner) != 1) return;
        step = MerchantFulfillmentJob.Step.ORDER;
      }
      int changed = job.operation() == Operation.CONFIRM
        ? orders.merchantConfirm(commandId, job.orderId(), job.merchantId())
        : orders.updateShipment(commandId, job.orderId(), job.merchantId(), job.carrier(), job.trackingNumber(),
          job.expectedVersion(), job.detail());
      if (changed == 1) {
        merchantJobs.finish(commandId, owner, Status.COMPLETED.name(), null);
      } else {
        Status outcome = job.operation() == Operation.CONFIRM ? Status.REJECTED : Status.REVIEW_REQUIRED;
        merchantJobs.finish(commandId, owner, outcome.name(), "ORDER_MUTATION_REJECTED");
      }
    } catch (RuntimeException failure) {
      merchantJobs.retry(commandId, owner, step.name() + "_ATTEMPT_FAILED");
    }
  }

  @Scheduled(fixedDelayString = "${commerceflow.fulfillment.shipment-delay:2s}")
  public void resumePending() {
    for (String commandId : jobs.dueCommands()) {
      execute(commandId, false);
    }
  }

  private void execute(String commandId, boolean immediate) {
    String owner = UUID.randomUUID().toString();
    if (jobs.claim(commandId, owner, immediate) != 1) return;
    ShipmentJob job = jobs.findCommand(commandId);
    Long orderId = job.orderId();
    Step step = job.step();
    try {
      if (step == Step.RESERVE) {
        boolean newAttempt = jobs.hasRejectedReservation(orderId);
        if (newAttempt && logistics.hasShipment(orderId)) {
          finish(job, owner, Status.REVIEW_REQUIRED, "EXISTING_LOGISTICS_EVIDENCE");
          return;
        }
        if (orders.reserveShipment(stepId(commandId, step.name()), orderId, job.merchantId(),
            job.carrier(), job.trackingNumber(), newAttempt) != 1) {
          finish(job, owner, Status.REJECTED, "ORDER_SHIPMENT_ADMISSION_REJECTED");
          return;
        }
        if (!advance(job, owner, Step.LOGISTICS)) return;
        step = Step.LOGISTICS;
      }
      if (step == Step.LOGISTICS) {
        logistics.execute(new Command(stepId(commandId, step.name()), LogisticsFulfillmentV1.Operation.ENSURE_SHIPMENT,
          orderId, job.orderNumber(), job.carrier(), job.trackingNumber(), null));
        if (!advance(job, owner, Step.FINISH_ORDER)) return;
        step = Step.FINISH_ORDER;
      }
      int changed = orders.merchantShip(stepId(commandId, step.name()), orderId, job.merchantId(),
        job.carrier(), job.trackingNumber());
      if (changed == 1) {
        finish(job, owner, Status.COMPLETED, null);
      } else {
        finish(job, owner, Status.REVIEW_REQUIRED, "ORDER_SHIPMENT_COMPLETION_REJECTED");
      }
    } catch (RuntimeException failure) {
      jobs.retry(commandId, owner, step.name() + "_ATTEMPT_FAILED");
    }
  }

  private boolean advance(ShipmentJob job, String owner, Step step) {
    return jobs.advance(job.commandId(), owner, step.name()) == 1;
  }

  private void finish(ShipmentJob job, String owner, Status status, String error) {
    jobs.finish(job.commandId(), owner, status.name(), error);
  }

  private String stepId(String commandId, String step) {
    String key = commandId + ":" + step;
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
  }
}
