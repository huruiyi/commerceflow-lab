package vip.fairy.commerceflow.inventory.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.contract.v1.DeliveredInventoryEvidenceV1;
import vip.fairy.commerceflow.inventory.application.contract.v1.DeliveredInventoryEvidenceV1.Line;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.DeliveredInventoryReconciliationMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;

@Service
public class DeliveredInventoryReconciliationService {

  private final DeliveredInventoryReconciliationMapper reconciliation;
  private final InventoryMapper inventory;
  private final ObjectMapper mapper;

  public DeliveredInventoryReconciliationService(DeliveredInventoryReconciliationMapper reconciliation,
                                                 InventoryMapper inventory, ObjectMapper mapper) {
    this.reconciliation = reconciliation;
    this.inventory = inventory;
    this.mapper = mapper;
  }

  @Transactional
  public Receipt reconcile(DeliveredInventoryEvidenceV1 request) {
    List<Line> lines = validate(request);
    String fingerprint = hash(mapper.writeValueAsString(lines));
    if (reconciliation.claim(request.orderId(), fingerprint, request.actor()) == 0) {
      if (!fingerprint.equals(reconciliation.fingerprint(request.orderId()))) {
        throw new InventoryBusinessRuleException("Order already has different warehouse evidence");
      }
      return receipt(request.orderId());
    }

    try {
      for (Line line : lines) {
        if (line.bookedMovementId() == null) {
          postMissing(request.orderId(), line);
        } else {
          linkExisting(request.orderId(), line);
        }
      }
    } catch (DuplicateKeyException exception) {
      throw new InventoryBusinessRuleException("Reservation, warehouse document line or movement is already in use");
    }
    return receipt(request.orderId());
  }

  private void postMissing(long orderId, Line line) {
    if (reconciliation.orderMovementCount(orderId, line.productId()) != 0) {
      throw new InventoryBusinessRuleException("Order product already has an inventory movement");
    }
    if (reconciliation.claimAvailable(line.productId(), line.quantity(), line.expectedAvailable()) != 1) {
      throw new InventoryBusinessRuleException("Available stock changed or is insufficient");
    }
    reconciliation.insertCommitted(orderId, line.productId(), line.quantity());
    String eventId = "delivered-reconciliation:" + orderId + ":" + line.productId();
    inventory.insertMovement(eventId, orderId, line.productId(), "RECONCILED_OUTBOUND",
      line.quantity(), inventory.inventory(line.productId()),
      "Warehouse evidence " + line.warehouseReference());
    reconciliation.record(orderId, line, reconciliation.movementId(eventId), "POSTED_MISSING");
  }

  private void linkExisting(long orderId, Line line) {
    Long movementId = reconciliation.bookedMovement(
      line.bookedMovementId(), orderId, line.productId(), line.quantity());
    if (movementId == null) {
      throw new InventoryBusinessRuleException("Existing movement does not match the order product and quantity");
    }
    reconciliation.record(orderId, line, movementId, "ALREADY_BOOKED");
  }

  private Receipt receipt(long orderId) {
    return new Receipt(orderId, reconciliation.movements(orderId));
  }

  private List<Line> validate(DeliveredInventoryEvidenceV1 request) {
    if (request == null || request.orderId() <= 0 || invalid(request.actor(), 100)
        || request.lines() == null || request.lines().isEmpty()) {
      throw new IllegalArgumentException("Order, reviewer and warehouse evidence are required");
    }
    Instant now = Instant.now();
    Set<Long> products = new HashSet<>();
    for (Line line : request.lines()) {
      if (line == null || line.productId() <= 0 || !products.add(line.productId())
          || line.quantity() <= 0 || invalid(line.warehouseReference(), 120)
          || invalid(line.note(), 300) || line.evidenceAt() == null || line.evidenceAt().isAfter(now)) {
        throw new IllegalArgumentException("Warehouse evidence line is invalid");
      }
      boolean alreadyBooked = line.bookedMovementId() != null;
      if (alreadyBooked ? line.expectedAvailable() != null || line.bookedMovementId() <= 0
          : line.expectedAvailable() == null || line.expectedAvailable() < line.quantity()) {
        throw new IllegalArgumentException("Supply either an existing movement or enough verified available stock");
      }
    }
    return request.lines().stream().sorted(Comparator.comparingLong(Line::productId)).toList();
  }

  private boolean invalid(String value, int maxLength) {
    return value == null || value.isBlank() || value.length() > maxLength;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record Receipt(long orderId, List<Long> movementIds) {
  }
}
