package vip.fairy.commerceflow.commerce.inventory.infrastructure;

import org.apache.ibatis.annotations.Param;

public interface InventoryCommandInboxMapper {

  int claim(
    @Param("commandId") String commandId,
    @Param("commandType") String commandType,
    @Param("orderId") Long orderId,
    @Param("payloadFingerprint") String payloadFingerprint
  );

  String payloadFingerprint(String commandId);

  int complete(String commandId);
}
