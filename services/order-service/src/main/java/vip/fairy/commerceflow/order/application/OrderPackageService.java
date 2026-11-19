package vip.fairy.commerceflow.order.application;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderLineRefundMapper;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderPackageMapper;

@Service
public class OrderPackageService {

  private final OrderLineRefundMapper orders;
  private final OrderPackageMapper packages;

  public OrderPackageService(OrderLineRefundMapper orders, OrderPackageMapper packages) {
    this.orders = orders;
    this.packages = packages;
  }

  @Transactional
  public Receipt reserve(Command command) {
    validate(command);
    var order = orders.lockOrder(command.orderId());
    if (order == null) return rejected();
    var prior = packages.find(command.packageId());
    if (prior != null) {
      if (prior.orderId() != command.orderId() || prior.merchantId() != command.merchantId()
          || !prior.carrier().equals(command.carrier()) || !prior.trackingNumber().equals(command.trackingNumber())) {
        return rejected();
      }
      var lines = packages.lines(command.packageId());
      var requested = command.lines().stream().sorted(Comparator.comparingLong(Line::orderLineId)).toList();
      var persisted = lines.stream().map(line -> new Line(line.orderLineId(), line.quantity())).toList();
      return persisted.equals(requested) ? new Receipt(true, lines) : rejected();
    }
    if (!"CONFIRMED".equals(order.status()) || !"COMMITTED".equals(order.inventoryStatus())
        || order.shipmentIntentKey() != null || orders.heldCount(command.orderId()) != 0) {
      return rejected();
    }
    if (orders.shipmentItems(command.orderId()).stream().anyMatch(item -> item.merchantId() != command.merchantId())
        || packages.trackingUsed(command.orderId(), command.carrier(), command.trackingNumber())) {
      return rejected();
    }
    for (Line line : command.lines()) {
      var item = orders.item(command.orderId(), line.orderLineId());
      if (item == null || item.merchantId() != command.merchantId()) return rejected();
      int remaining = item.quantity() - orders.settledQuantity(command.orderId(), line.orderLineId())
        - packages.allocatedQuantity(line.orderLineId());
      if (line.quantity() > remaining) return rejected();
    }
    if (packages.hold(command.orderId(), command.packageId()) != 1) {
      throw new IllegalStateException("Could not hold shipment admission for package");
    }
    packages.insert(command);
    for (Line line : command.lines()) packages.insertLine(command.packageId(), line);
    return new Receipt(true, packages.lines(command.packageId()));
  }

  @Transactional
  public boolean finish(Finish command) {
    if (command == null || command.orderId() <= 0 || command.merchantId() <= 0) {
      throw new IllegalArgumentException("Order and merchant are required");
    }
    requirePackageId(command.packageId());
    var order = orders.lockOrder(command.orderId());
    if (order == null) return false;
    var allocation = packages.find(command.packageId());
    if (allocation == null || command.orderId() != allocation.orderId()
        || command.merchantId() != allocation.merchantId()) return false;
    if (allocation.posted()) return true;
    if (!command.packageId().equals(order.shipmentIntentKey()) || !"CONFIRMED".equals(order.status())) return false;
    if (packages.markPosted(command.packageId()) != 1
        || packages.clearHold(command.orderId(), command.packageId()) != 1) {
      throw new IllegalStateException("Package shipment admission changed during finalization");
    }
    packages.finishIfFullyAllocated(command.orderId());
    return true;
  }

  private void validate(Command command) {
    if (command == null || command.orderId() <= 0 || command.merchantId() <= 0
        || command.carrier() == null || command.carrier().isBlank() || command.carrier().length() > 100
        || command.trackingNumber() == null || command.trackingNumber().isBlank()
        || command.trackingNumber().length() > 120 || command.lines() == null || command.lines().isEmpty()) {
      throw new IllegalArgumentException("Invalid package allocation");
    }
    requirePackageId(command.packageId());
    var seen = new HashSet<Long>();
    for (Line line : command.lines()) {
      if (line == null || line.orderLineId() <= 0 || line.quantity() <= 0 || !seen.add(line.orderLineId())) {
        throw new IllegalArgumentException("Invalid or duplicate package line");
      }
    }
  }

  private void requirePackageId(String packageId) {
    if (packageId == null || !UUID.fromString(packageId).toString().equals(packageId)) {
      throw new IllegalArgumentException("Canonical package ID is required");
    }
  }

  private Receipt rejected() {
    return new Receipt(false, List.of());
  }

  public record Command(String packageId, long orderId, long merchantId, String carrier, String trackingNumber,
                        List<Line> lines) {
  }

  public record Line(long orderLineId, int quantity) {
  }

  public record PostedLine(long orderLineId, long productId, int quantity) {
  }

  public record Receipt(boolean accepted, List<PostedLine> lines) {
  }

  public record Finish(String packageId, long orderId, long merchantId) {
  }
}
