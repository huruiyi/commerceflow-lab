package vip.fairy.commerceflow.commerce.merchant.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.Count;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.Dispatch;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.InventoryLine;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.PlannedLine;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementFulfillmentV1.WarehouseEvidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatementFulfillmentQueryTest {

  @Test
  void zeroDispatchedIsQuantityEvidenceEvenWhenNothingShouldBePosted() {
    var now = Instant.now();
    var dispatch = new Dispatch("dispatch", 1, 2, 3, "DISPATCHED",
      List.of(new PlannedLine(4, 8, 3), new PlannedLine(5, 9, 2), new PlannedLine(6, 10, 1)),
      List.of(new Count(4, 0), new Count(5, 0), new Count(6, 1)), now, now);
    var voided = new Dispatch("voided", 1, 2, 3, "VOIDED", List.of(new PlannedLine(7, 11, 2)),
      List.of(new Count(7, 0)), now, now);
    var posted = List.of(new InventoryLine("dispatch", 1, 3, 4, 8, 3, now),
      new InventoryLine("dispatch", 1, 3, 6, 10, 1, now), new InventoryLine("voided", 1, 3, 7, 11, 2, now));
    var queries = new StatementFulfillmentQuery((merchant, orders) -> Optional.of(posted),
      (merchant, orders) -> Optional.of(new WarehouseEvidence(List.of(dispatch, voided), List.of())));
    var result = queries.load(2, List.of(1L)).check(1, Map.of());
    assertEquals(List.of(new StatementFulfillmentQuery.StockDifference(
      "INVENTORY_OUTBOUND_QUANTITY", "dispatch", 4, 0, 3), new StatementFulfillmentQuery.StockDifference(
      "INVENTORY_OUTBOUND_QUANTITY", "voided", 7, 0, 2)), result.differences());
  }
}
