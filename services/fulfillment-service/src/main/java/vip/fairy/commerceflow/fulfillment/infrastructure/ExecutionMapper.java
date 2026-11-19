package vip.fairy.commerceflow.fulfillment.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;
import vip.fairy.commerceflow.fulfillment.domain.FulfillmentCommand;
import vip.fairy.commerceflow.fulfillment.domain.RecoveryRecord;

public interface ExecutionMapper {

  int initializeMode(@Param("mode") String mode);

  String mode();

  String lockReplicationMode();

  FulfillmentRuntimeMetrics.Snapshot runtimeMetrics();

  int lockOrder(@Param("orderId") long orderId);

  int reserveCommand(@Param("commandId") String commandId, @Param("orderId") long orderId);

  Long commandOrder(@Param("commandId") String commandId);

  boolean hasOpenTask(@Param("orderId") long orderId);

  boolean businessSlotOccupied(ExecutionTask task);

  boolean hasRejectedReservation(@Param("orderId") long orderId);

  int insert(ExecutionTask task);

  ExecutionTask find(@Param("commandId") String commandId);

  ExecutionTask shipment(@Param("orderId") long orderId);

  String legacySlot(@Param("command") FulfillmentCommand command);

  List<String> due();

  int claim(@Param("task") ExecutionTask task, @Param("owner") String owner);

  int advance(@Param("task") ExecutionTask task, @Param("owner") String owner, @Param("step") String step);
  int waitForWarehouse(@Param("task") ExecutionTask task, @Param("owner") String owner);

  int finish(@Param("task") ExecutionTask task, @Param("owner") String owner,
             @Param("status") String status, @Param("error") String error);

  int retry(@Param("task") ExecutionTask task, @Param("owner") String owner, @Param("error") String error);

  List<String> reviewable(@Param("limit") int limit, @Param("shipment") Boolean shipment);

  String lockTask(@Param("task") ExecutionTask task);

  int requeue(@Param("task") ExecutionTask task);

  RecoveryRecord recovery(@Param("task") ExecutionTask task, @Param("recoveryId") String recoveryId);

  List<RecoveryRecord> recoveryHistory(@Param("task") ExecutionTask task);

  List<RecoveryRecord> shipmentRecoveryHistory(@Param("orderId") long orderId);

  int insertRecovery(@Param("task") ExecutionTask task, @Param("record") RecoveryRecord record);
}
