package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentRecovery;

public interface ShipmentJobMapper {

  int insert(ShipmentJob job);

  ShipmentJob find(@Param("orderId") Long orderId);

  ShipmentJob findSlot(@Param("orderId") Long orderId);

  ShipmentJob findCommand(@Param("commandId") String commandId);

  boolean hasRejectedReservation(@Param("orderId") Long orderId);

  ShipmentJob lockForRecovery(@Param("commandId") String commandId);

  List<ShipmentJob> reviewable(@Param("limit") int limit);

  ShipmentRecovery findRecovery(@Param("orderId") Long orderId, @Param("recoveryId") String recoveryId);

  List<ShipmentRecovery> recoveryHistory(@Param("orderId") Long orderId);

  int insertRecovery(ShipmentRecovery recovery);

  int requeueReview(@Param("commandId") String commandId);

  List<String> dueCommands();

  int claim(@Param("commandId") String commandId, @Param("owner") String owner, @Param("immediate") boolean immediate);

  int advance(@Param("commandId") String commandId, @Param("owner") String owner, @Param("step") String step);

  int finish(@Param("commandId") String commandId, @Param("owner") String owner, @Param("status") String status,
             @Param("error") String error);

  int retry(@Param("commandId") String commandId, @Param("owner") String owner, @Param("error") String error);
}
