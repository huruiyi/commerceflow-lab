package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import org.apache.ibatis.annotations.Param;

public interface InventoryCommandInboxMapper {

  int claim(@Param("commandId") String commandId,
            @Param("commandType") String commandType,
            @Param("orderId") Long orderId,
            @Param("payloadFingerprint") String payloadFingerprint);

  String payloadFingerprint(String commandId);

  String rejectionMessage(String commandId);

  int complete(String commandId);

  int reject(@Param("commandId") String commandId, @Param("message") String message);
}
