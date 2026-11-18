package vip.fairy.commerceflow.commerce.inventory.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryEvidenceV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryEvidenceV1.Line;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryEvidenceV1.Receipt;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryOrderV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.DeliveredInventoryRemoteClient;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class DeliveredInventoryRecoveryService {

  private static final String CLOSURE_REASON = "Warehouse evidence reconciled delivered inventory";

  private final InventoryCommandDeliveryMapper deliveries;
  private final InventoryCommandPort commands;
  private final DeliveredInventoryOrderV1 orders;
  private final DeliveredInventoryRemoteClient inventory;

  public DeliveredInventoryRecoveryService(InventoryCommandDeliveryMapper deliveries, InventoryCommandPort commands,
                                            DeliveredInventoryOrderV1 orders,
                                            DeliveredInventoryRemoteClient inventory) {
    this.deliveries = deliveries;
    this.commands = commands;
    this.orders = orders;
    this.inventory = inventory;
  }

  @Transactional
  public Receipt reconcile(String commandId, String actor, List<Evidence> evidence) {
    commands.requireWritable();
    var delivery = deliveries.lockRecovery(commandId);
    if (delivery == null) {
      throw new ResourceNotFoundException("Inventory delivery was not found");
    }
    boolean replay = "CLOSED".equals(delivery.status()) && CLOSURE_REASON.equals(delivery.recoveryReason());
    if (!"RESERVE".equals(delivery.commandType())
        || (!replay && !"RECONCILIATION_REQUIRED".equals(delivery.status()))) {
      throw new BusinessRuleException("Only unresolved reservations can be reconciled with warehouse evidence");
    }
    var order = orders.requireDelivered(delivery.orderId());
    Set<Long> products = new HashSet<>();
    for (Evidence line : evidence) {
      if (line == null || !products.add(line.productId()) || line.evidenceAt() == null
          || line.evidenceAt().isBefore(order.createdAt())) {
        throw new IllegalArgumentException("Each order product needs one dated warehouse evidence line");
      }
    }
    if (products.isEmpty() || !products.equals(order.quantities().keySet())) {
      throw new IllegalArgumentException("Warehouse evidence must cover all order products");
    }
    List<Line> lines = evidence.stream().map(line -> new Line(line.productId(),
      order.quantities().get(line.productId()), line.warehouseReference(), line.evidenceAt(),
      line.note(), line.expectedAvailable(), line.bookedMovementId())).toList();
    Receipt receipt = inventory.reconcile(new DeliveredInventoryEvidenceV1(delivery.orderId(), actor, lines));
    if (!replay) {
      orders.markReconciled(delivery.orderId());
      if (deliveries.closeReconciled(commandId, actor, CLOSURE_REASON) != 1) {
        throw new BusinessRuleException("Inventory delivery changed during reconciliation");
      }
    }
    return receipt;
  }

  public record Evidence(Long productId, String warehouseReference, Instant evidenceAt,
                         String note, Integer expectedAvailable, Long bookedMovementId) {
  }
}
