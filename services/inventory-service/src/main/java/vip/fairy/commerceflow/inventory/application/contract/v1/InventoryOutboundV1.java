package vip.fairy.commerceflow.inventory.application.contract.v1;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class InventoryOutboundV1 {

  private InventoryOutboundV1() {
  }

  public record Document(UUID outboundId, long orderId, long merchantId, List<Line> lines,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Boolean partial) {
    public Document {
      if (Boolean.FALSE.equals(partial)) partial = null;
    }
  }

  public record Line(long orderLineId, long productId, int quantity) {
  }

  public record Receipt(UUID outboundId, long orderId, int lineCount) {
  }

  public record Event(int schemaVersion, String eventType, String eventId, UUID outboundId,
                      long orderId, long merchantId, long orderLineId, long productId,
                      int quantity, Instant occurredAt) {
  }
}
