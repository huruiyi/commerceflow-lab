package vip.fairy.commerceflow.commerce.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandSemantics;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandValidatorV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper;

@Service
public class InventoryCommandGateway implements InventoryCommandPort {

  private final InventoryCommandValidatorV1 validator;
  private final InventoryCommandHandlerV1 handler;
  private final InventoryCommandDeliveryMapper delivery;
  private final ObjectMapper mapper;

  public InventoryCommandGateway(InventoryCommandValidatorV1 validator,
                                 InventoryCommandHandlerV1 handler,
                                 InventoryCommandDeliveryMapper delivery,
                                 ObjectMapper mapper) {
    this.validator = validator;
    this.handler = handler;
    this.delivery = delivery;
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public void handle(InventoryCommandV1 command) {
    validator.validate(command);
    String fingerprint = InventoryCommandSemantics.fingerprint(command);
    String payload = payload(command);
    if (delivery.enqueue(command.commandId(), command.commandType().name(), command.producer(),
      command.orderId(), fingerprint, payload) != 1) {
      if (!fingerprint.equals(delivery.payloadFingerprint(command.commandId()))) {
        throw new IllegalArgumentException("Inventory delivery commandId has conflicting payload");
      }
      if ("ACKNOWLEDGED".equals(delivery.status(command.commandId()))) {
        return;
      }
    }
    handler.handle(command);
    InventoryCommandResultV1 result = InventoryCommandResultV1.succeeded(command);
    result.validate();
    delivery.recordResult(result.resultId(), result.commandId(), result.commandType().name(),
      result.outcome().name(), result.occurredAt(), result.errorCode(), result.message(),
      result.correlationId(), result.causationId(), result.traceparent());
    if (delivery.acknowledge(command.commandId()) != 1) {
      throw new IllegalStateException("Inventory command delivery changed concurrently");
    }
  }

  private String payload(InventoryCommandV1 command) {
    try {
      return mapper.writeValueAsString(command);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory command cannot be serialized", exception);
    }
  }
}
