package vip.fairy.commerceflow.inventory.application.contract.v1;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class InventoryOutboundV1 {

  private InventoryOutboundV1() {
  }

  public record Document(UUID outboundId, long orderId, long merchantId, List<Line> lines,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Boolean partial,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Long warehouseId) {
    public Document {
      if (Boolean.FALSE.equals(partial)) partial = null;
    }

    public Document(UUID outboundId, long orderId, long merchantId, List<Line> lines, Boolean partial) {
      this(outboundId, orderId, merchantId, lines, partial, null);
    }

    public long warehouse() {
      return warehouseId == null ? 1 : warehouseId;
    }
  }

  public record Line(long orderLineId, long productId, int quantity) {
  }

  public record Receipt(UUID outboundId, long orderId, int lineCount) {
  }

  public record Event(int schemaVersion, String eventType, String eventId, UUID outboundId,
                      long orderId, long merchantId, long orderLineId, long productId,
                      int quantity, Instant occurredAt, long warehouseId) {
  }
}
