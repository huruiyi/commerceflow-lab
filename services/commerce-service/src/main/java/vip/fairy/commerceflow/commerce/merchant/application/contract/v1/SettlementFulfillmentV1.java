package vip.fairy.commerceflow.commerce.merchant.application.contract.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only outbound and return facts for one merchant's statement page. */
public final class SettlementFulfillmentV1 {

  private SettlementFulfillmentV1() {
  }

  public interface InventorySource {

    Optional<List<InventoryLine>> outboundEvidence(long merchantId, List<Long> orderIds);
  }

  public interface WarehouseSource {

    Optional<WarehouseEvidence> warehouseEvidence(long merchantId, List<Long> orderIds);
  }

  public record InventoryLine(String outboundId, long orderId, long warehouseId, long orderLineId, long productId,
                              int quantity, Instant postedAt) {
  }

  public record WarehouseEvidence(List<Dispatch> outbounds, List<ReturnReceipt> returns) {
  }

  public record Dispatch(String outboundId, long orderId, long merchantId, long warehouseId, String status,
                         List<PlannedLine> planned, List<Count> actual, Instant createdAt, Instant decidedAt) {
  }

  public record PlannedLine(long orderLineId, long productId, int quantity) {
  }

  public record Count(long orderLineId, int quantity) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReturnReceipt(String returnId, String outboundId, long orderId, long orderLineId, long warehouseId,
                              int expectedQuantity, Integer receivedQuantity, String status, Instant receivedAt,
                              Instant inspectedAt, String supplementId, Integer qualifiedQuantity,
                              Instant supplementAt) {
  }
}
