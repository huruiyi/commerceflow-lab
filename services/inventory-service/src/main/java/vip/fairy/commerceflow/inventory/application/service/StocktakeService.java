package vip.fairy.commerceflow.inventory.application.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.contract.v1.StocktakeV1;
import vip.fairy.commerceflow.inventory.application.contract.v1.StocktakeV1.Baseline;
import vip.fairy.commerceflow.inventory.application.contract.v1.StocktakeV1.Receipt;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.DeliveredInventoryReconciliationMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.StocktakeMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.WarehouseStockMapper;

@Service
public class StocktakeService {

  private final StocktakeMapper stocktakes;
  private final DeliveredInventoryReconciliationMapper reconciliations;
  private final InventoryMapper inventory;
  private final ObjectMapper json;
  private final WarehouseStockMapper warehouses;

  public StocktakeService(StocktakeMapper stocktakes, DeliveredInventoryReconciliationMapper reconciliations,
                          InventoryMapper inventory, ObjectMapper json, WarehouseStockMapper warehouses) {
    this.stocktakes = stocktakes;
    this.reconciliations = reconciliations;
    this.inventory = inventory;
    this.json = json;
    this.warehouses = warehouses;
  }

  @Transactional
  public Baseline baseline(long productId) {
    var stock = inventory.lockInventory(productId);
    if (stock == null) {
      throw new InventoryBusinessRuleException("Product has no current inventory");
    }
    var lastMovement = stocktakes.lastMovement(productId);
    return new Baseline(productId, stock.stockAvailable(), stock.stockReserved(), stock.stockSold(),
      lastMovement == null ? 0 : lastMovement.id(), lastMovement == null ? null : lastMovement.createdAt());
  }

  @Transactional
  public Receipt apply(StocktakeV1 request) {
    validate(request);
    String audit = json.writeValueAsString(request);
    String fingerprint = DeliveredInventoryReconciliationService.hash("stocktake:" + audit);
    int adjustment = request.countedAvailable() - request.baseline().available();
    if (stocktakes.claim(request.reference(), fingerprint, audit, adjustment) == 0) {
      if (!fingerprint.equals(stocktakes.fingerprint(request.reference()))) {
        throw new InventoryBusinessRuleException("Stocktake reference already has a different approval");
      }
      return stocktakes.receipt(request.reference());
    }

    for (long orderId : request.orderIds()) {
      if (reconciliations.claim(orderId, fingerprint, request.approvedBy()) != 1
          || stocktakes.orderReservation(orderId) != null || stocktakes.orderMovement(orderId) != null) {
        throw new InventoryBusinessRuleException("Order already has inventory evidence, a movement or a reservation");
      }
      stocktakes.link(orderId, request.reference());
    }

    if (!request.baseline().equals(baseline(request.productId()))) {
      throw new InventoryBusinessRuleException("Inventory changed after the baseline. Record a fresh count");
    }
    if (request.baseline().movementAt() != null && request.countedAt().isBefore(request.baseline().movementAt())) {
      throw new InventoryBusinessRuleException("Stocktake count predates the most recent inventory movement");
    }
    if (adjustment != 0) {
      stocktakes.setAvailable(request.productId(), request.countedAvailable());
      if (warehouses.adjust(request.productId(), 1, adjustment, 0, 0) != 1) {
        throw new InventoryBusinessRuleException("Count adjustment exceeds default warehouse stock");
      }
      String eventId = "stocktake:" + request.reference();
      inventory.insertMovement(eventId, null, request.productId(), "CYCLE_COUNT_ADJUSTMENT",
        Math.abs(adjustment), inventory.lockInventory(request.productId()), "Stocktake " + request.reference());
      stocktakes.attachMovement(request.reference(), reconciliations.movementId(eventId));
    }
    return stocktakes.receipt(request.reference());
  }

  private void validate(StocktakeV1 request) {
    if (request == null || request.reference() == null || !request.reference().matches("[A-Za-z0-9_-]{1,80}")
        || request.productId() <= 0 || request.baseline() == null
        || request.baseline().productId() != request.productId() || request.baseline().available() < 0
        || request.countedAvailable() < 0 || request.countedAt() == null || request.countedAt().isAfter(Instant.now())
        || invalid(request.note(), 300) || invalid(request.countedBy(), 100) || invalid(request.approvedBy(), 100)
        || request.countedBy().equalsIgnoreCase(request.approvedBy())
        || request.orderIds() == null || request.orderIds().isEmpty() || request.orderIds().size() > 100) {
      throw new IllegalArgumentException("A dated count and independent approval are required");
    }
    long previous = 0;
    for (Long orderId : request.orderIds()) {
      if (orderId == null || orderId <= previous) {
        throw new IllegalArgumentException("Order IDs must be unique, positive and sorted");
      }
      previous = orderId;
    }
  }

  private boolean invalid(String value, int maxLength) {
    return value == null || value.isBlank() || value.length() > maxLength;
  }
}
