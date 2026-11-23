package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.EvidenceLine;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.EvidenceQuery;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.Line;
import vip.fairy.commerceflow.inventory.domain.OutboundProductQuantity;

public interface InventoryOutboundMapper {

  int claim(@Param("outboundId") String outboundId, @Param("orderId") long orderId,
            @Param("merchantId") long merchantId, @Param("fingerprint") String fingerprint,
            @Param("warehouseId") long warehouseId);

  String fingerprint(String outboundId);

  List<OutboundProductQuantity> postedQuantities(@Param("orderId") long orderId,
                                                @Param("warehouseId") long warehouseId);
  List<EvidenceLine> evidence(EvidenceQuery query);

  void insertLine(@Param("outboundId") String outboundId, @Param("line") Line line);

  void enqueue(@Param("eventId") String eventId, @Param("outboundId") String outboundId,
               @Param("orderLineId") long orderLineId, @Param("payload") String payload);

  List<PendingEvent> pending(int limit);

  Backlog backlog();

  int published(String eventId);

  int retry(String eventId);

  record PendingEvent(String eventId, String payload) {
  }

  record Backlog(long pending, long oldestSeconds) {
  }
}
