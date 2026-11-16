package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import org.apache.ibatis.annotations.Param;

public interface InventoryCatalogEventInboxMapper {

  int claim(@Param("eventId") String eventId, @Param("productId") Long productId,
            @Param("businessVersion") long businessVersion,
            @Param("payloadFingerprint") String payloadFingerprint);

  String payloadFingerprint(String eventId);

  int complete(@Param("eventId") String eventId, @Param("status") String status);
}
