package vip.fairy.commerceflow.commerce.merchant.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.Dispatch;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.InventoryLine;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.InventorySource;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.ReturnReceipt;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.WarehouseEvidence;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.WarehouseSource;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementRefundV1.Refund;

@Component
public class StatementFulfillmentQuery {

  private final InventorySource inventory;
  private final WarehouseSource warehouse;

  StatementFulfillmentQuery(InventorySource inventory, WarehouseSource warehouse) {
    this.inventory = inventory;
    this.warehouse = warehouse;
  }

  Page load(long merchant, List<Long> orderIds) {
    var outbound = inventory.outboundEvidence(merchant, orderIds);
    var receipts = warehouse.warehouseEvidence(merchant, orderIds);
    return new Page(outbound.isPresent(), receipts.isPresent(),
      group(outbound.orElse(List.of()), InventoryLine::orderId),
      group(receipts.map(WarehouseEvidence::outbounds).orElse(List.of()), Dispatch::orderId),
      group(receipts.map(WarehouseEvidence::returns).orElse(List.of()), ReturnReceipt::orderId));
  }

  private static <T> Map<Long, List<T>> group(List<T> rows, Function<T, Long> orderId) {
    return rows.stream().collect(Collectors.groupingBy(orderId));
  }

  record Page(boolean inventoryAvailable, boolean warehouseAvailable, Map<Long, List<InventoryLine>> inventory,
              Map<Long, List<Dispatch>> dispatches, Map<Long, List<ReturnReceipt>> returns) {

    FulfillmentCheck check(long orderId, Map<String, Refund> afterSales) {
      var posted = inventory.getOrDefault(orderId, List.of());
      var shipped = dispatches.getOrDefault(orderId, List.of());
      var returnsForOrder = returns.getOrDefault(orderId, List.of()).stream()
        .map(receipt -> linkRefund(receipt, afterSales.get(receipt.returnId())))
        .toList();
      var differences = inventoryAvailable && warehouseAvailable
        ? compare(posted, shipped) : List.<StockDifference>of();
      return new FulfillmentCheck(inventoryAvailable, warehouseAvailable, posted, shipped,
        returnsForOrder, differences);
    }
  }

  private static ReturnCheck linkRefund(ReturnReceipt receipt, Refund refund) {
    if (refund == null || !Objects.equals(refund.line(), receipt.orderLineId()) || refund.number() == null) {
      return new ReturnCheck(receipt, null, null);
    }
    return new ReturnCheck(receipt, refund.number(), "COMPLETED".equals(refund.status()) ? refund.quantity() : null);
  }

  private static List<StockDifference> compare(List<InventoryLine> posted, List<Dispatch> shipped) {
    Map<OutboundLine, Integer> inventory = new LinkedHashMap<>();
    posted.forEach(line -> inventory.put(
      new OutboundLine(line.outboundId(), line.warehouseId(), line.orderLineId(), line.productId()), line.quantity()));
    List<StockDifference> differences = new ArrayList<>();
    for (var dispatch : shipped) {
      if (!"DISPATCHED".equals(dispatch.status()) || dispatch.actual() == null) continue;
      var lines = dispatch.planned().stream().collect(Collectors.toMap(
        line -> line.orderLineId(), line -> line.productId()));
      for (var actual : dispatch.actual()) {
        if (actual.quantity() == 0) continue;
        var product = lines.get(actual.orderLineId());
        if (product == null) continue;
        var key = new OutboundLine(dispatch.outboundId(), dispatch.warehouseId(), actual.orderLineId(), product);
        var quantity = inventory.remove(key);
        if (quantity == null || quantity != actual.quantity()) {
          differences.add(new StockDifference("INVENTORY_OUTBOUND_QUANTITY", key.outboundId(), key.orderLineId(),
            actual.quantity(), quantity));
        }
      }
    }
    inventory.forEach((key, quantity) -> differences.add(new StockDifference("WMS_OUTBOUND_MISSING",
      key.outboundId(), key.orderLineId(), null, quantity)));
    return differences;
  }

  private record OutboundLine(String outboundId, long warehouseId, long orderLineId, long productId) {
  }

  public record StockDifference(String kind, String outboundId, long orderLineId, Integer expected, Integer observed) {
  }

  public record ReturnCheck(ReturnReceipt receipt, String refundNumber, Integer refundedQuantity) {
  }

  public record FulfillmentCheck(boolean inventoryAvailable, boolean warehouseAvailable, List<InventoryLine> inventory,
                                 List<Dispatch> dispatches, List<ReturnCheck> returns,
                                 List<StockDifference> differences) {
  }
}
