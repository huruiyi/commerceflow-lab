package vip.fairy.commerceflow.commerce.inventory.infrastructure;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface InventoryCommandDeliveryMapper {

  int enqueue(@Param("commandId") String commandId,
              @Param("commandType") String commandType,
              @Param("producer") String producer,
              @Param("orderId") Long orderId,
              @Param("payloadFingerprint") String payloadFingerprint,
              @Param("payload") String payload);

  String payloadFingerprint(String commandId);

  String status(String commandId);

  List<String> dispatchable(int limit);

  int markInFlight(@Param("commandId") String commandId,
                   @Param("resultDeadlineAt") Instant resultDeadlineAt);

  int acknowledge(String commandId);

  int recordResult(@Param("resultId") String resultId,
                   @Param("commandId") String commandId,
                   @Param("commandType") String commandType,
                   @Param("outcome") String outcome,
                   @Param("occurredAt") Instant occurredAt,
                   @Param("errorCode") String errorCode,
                   @Param("message") String message,
                   @Param("correlationId") String correlationId,
                   @Param("causationId") String causationId,
                   @Param("traceparent") String traceparent);

  int recoverExpired(@Param("maxAttempts") int maxAttempts,
                     @Param("nextAttemptAt") Instant nextAttemptAt);

  List<DeliveryRecoveryView> expiredForReconciliation(int maxAttempts);

  List<DeliveryRecoveryView> reconciliationRequired(int limit);

  DeliveryRecoveryView byCommandId(String commandId);

  DeliveryRecoveryView lockRecovery(String commandId);

  int closeReconciled(@Param("commandId") String commandId,
                      @Param("recoveredBy") String recoveredBy,
                      @Param("recoveryReason") String recoveryReason);

  String payload(String commandId);

  int reopen(@Param("commandId") String commandId,
             @Param("recoveredBy") String recoveredBy,
             @Param("recoveryReason") String recoveryReason);
}
