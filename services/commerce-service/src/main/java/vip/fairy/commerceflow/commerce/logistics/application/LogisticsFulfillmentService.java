package vip.fairy.commerceflow.commerce.logistics.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1;
import vip.fairy.commerceflow.commerce.logistics.domain.ShipmentRecordView;
import vip.fairy.commerceflow.commerce.logistics.infrastructure.LogisticsFulfillmentCommandMapper;
import vip.fairy.commerceflow.commerce.logistics.infrastructure.ShipmentMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@Service
public class LogisticsFulfillmentService implements LogisticsFulfillmentV1 {

  private final LogisticsFulfillmentCommandMapper commands;
  private final ShipmentMapper shipments;
  private final LogisticsGatewayClient gateway;
  private final TransactionTemplate transactions;

  public LogisticsFulfillmentService(LogisticsFulfillmentCommandMapper commands, ShipmentMapper shipments,
                                     LogisticsGatewayClient gateway, PlatformTransactionManager manager) {
    this.commands = commands;
    this.shipments = shipments;
    this.gateway = gateway;
    this.transactions = new TransactionTemplate(manager);
  }

  @Override
  public Receipt execute(Command command) {
    String id = command.commandId();
    commands.register(command);
    if (!command.equals(commands.find(id))) {
      throw new BusinessRuleException("Logistics command ID was reused with different parameters");
    }
    Receipt saved = commands.findReceipt(id);
    if (saved != null) return saved;
    String owner = UUID.randomUUID().toString();
    if (commands.claim(id, owner) != 1) {
      return findReceipt(id).orElseThrow(() -> new BusinessRuleException("Logistics command is already executing"));
    }
    try {
      LogisticsGatewayClient.Result result = callChannel(command);
      validate(command, result);
      Receipt receipt = new Receipt(id, result.platformShipmentNo(), result.shipmentVersion());
      transactions.executeWithoutResult(status -> {
        if (commands.complete(receipt, owner) != 1) {
          throw new BusinessRuleException("Logistics command lease expired before completion");
        }
        applyProjection(command, result);
      });
      return receipt;
    } catch (RuntimeException failure) {
      commands.release(id, owner);
      throw failure;
    }
  }

  @Override
  public Optional<Receipt> findReceipt(String commandId) {
    return Optional.ofNullable(commands.findReceipt(commandId));
  }

  @Override
  public boolean hasShipment(Long orderId) {
    return shipments.byOrder(orderId).isPresent() || gateway.findShipment(orderId).isPresent();
  }

  private LogisticsGatewayClient.Result callChannel(Command command) {
    Long order = command.orderId();
    if (command.operation() == Operation.ENSURE_SHIPMENT) {
      shipments.prepare(order, reference(order), command.carrier(), command.trackingNumber());
      return gateway.findShipment(order).orElseGet(() ->
        gateway.create(order, command.orderNumber(), command.carrier(), command.trackingNumber()));
    }
    return gateway.update(order, command.carrier(), command.trackingNumber(), command.expectedVersion());
  }

  private void validate(Command command, LogisticsGatewayClient.Result result) {
    long expected = command.operation() == Operation.ENSURE_SHIPMENT ? 0 : command.expectedVersion() + 1;
    if (result == null || result.shipmentVersion() != expected || result.platformShipmentNo() == null
        || !reference(command.orderId()).equals(result.merchantShipmentNo())
        || !command.carrier().equals(result.carrier()) || !command.trackingNumber().equals(result.trackingNumber())
        || (command.operation() == Operation.ENSURE_SHIPMENT && !command.orderNumber().equals(result.orderNumber()))) {
      throw new BusinessRuleException("Logistics response does not match the original command");
    }
  }

  private void applyProjection(Command command, LogisticsGatewayClient.Result result) {
    ShipmentRecordView projection = shipments.lockByMerchantNo(reference(command.orderId())).orElseThrow();
    boolean samePlatform = Objects.equals(projection.getPlatformShipmentNo(), result.platformShipmentNo());
    boolean sameDetails = command.carrier().equals(projection.getCarrier())
      && command.trackingNumber().equals(projection.getTrackingNumber());
    if (command.operation() == Operation.ENSURE_SHIPMENT && projection.getPlatformShipmentNo() == null
        && command.orderId().equals(projection.getOrderId()) && projection.getShipmentVersion() == 0 && sameDetails) {
      shipments.attach(projection.getId(), result.platformShipmentNo(), result.carrier(),
        result.trackingNumber(), result.status());
      return;
    }
    if (command.orderId().equals(projection.getOrderId()) && samePlatform) {
      if (projection.getShipmentVersion() == result.shipmentVersion() && sameDetails) return;
      if (command.operation() == Operation.UPDATE_DETAILS
          && projection.getShipmentVersion() < result.shipmentVersion()
          && shipments.updateDetails(command.orderId(), command.carrier(), command.trackingNumber(),
            result.shipmentVersion()) == 1) return;
    }
    throw new BusinessRuleException("Shipment projection conflicts with the logistics response");
  }

  private String reference(Long orderId) {
    return "CFL-ORDER-" + orderId;
  }
}
