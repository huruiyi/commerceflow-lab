package vip.fairy.commerceflow.commerce.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandSemantics;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandValidatorV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.InventoryWriteFreezeGate;

@Service
public class InventoryCommandGateway implements InventoryCommandPort {

  private final InventoryCommandValidatorV1 validator;
  private final InventoryCommandDeliveryMapper delivery;
  private final ObjectMapper mapper;
  private final InventoryWriteFreezeGate writeFreeze;

  public InventoryCommandGateway(InventoryCommandValidatorV1 validator,
                                 InventoryCommandDeliveryMapper delivery,
                                 ObjectMapper mapper,
                                 InventoryWriteFreezeGate writeFreeze) {
    this.validator = validator;
    this.delivery = delivery;
    this.mapper = mapper;
    this.writeFreeze = writeFreeze;
  }

  @Override
  public void requireWritable() {
    writeFreeze.requireWritable();
  }

  @Override
  @Transactional
  public void handle(InventoryCommandV1 command) {
    requireWritable();
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
  }

  private String payload(InventoryCommandV1 command) {
    try {
      return mapper.writeValueAsString(command);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory command cannot be serialized", exception);
    }
  }
}
