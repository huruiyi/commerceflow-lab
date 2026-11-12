package vip.fairy.commerceflow.simulator.logistics;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 物流模拟器的持久化契约。带行锁、条件状态转换和投递箱领取的 SQL 集中定义在 Mapper XML。
 */
public interface LogisticsDataMapper {

  int insertShipment(@Param("merchantShipmentNo") String merchantShipmentNo,
                     @Param("platformShipmentNo") String platformShipmentNo,
                     @Param("orderNumber") String orderNumber,
                     @Param("carrier") String carrier,
                     @Param("trackingNumber") String trackingNumber,
                     @Param("callbackUrl") String callbackUrl);

  Long shipmentIdByMerchantNo(String merchantShipmentNo);

  Map<String, Object> findShipmentByMerchantNo(String merchantShipmentNo);

  Map<String, Object> lockShipmentByMerchantNo(String merchantShipmentNo);

  Map<String, Object> lockShipmentById(Long id);

  Map<String, Object> shipmentById(Long id);

  List<Map<String, Object>> shipmentEvents(Long shipmentId);

  int transitionToException(@Param("id") Long id, @Param("status") String status);

  String resumeStatus(Long id);

  int recoverShipment(Long id);

  int updateShipmentDetails(@Param("merchantShipmentNo") String merchantShipmentNo,
                            @Param("carrier") String carrier,
                            @Param("trackingNumber") String trackingNumber);

  List<Long> automaticCandidates(@Param("updatedBefore") Instant updatedBefore,
                                 @Param("limit") int limit);

  int advanceShipment(@Param("id") Long id, @Param("currentStatus") String currentStatus,
                      @Param("nextStatus") String nextStatus);

  int insertShipmentEvent(@Param("shipmentId") Long shipmentId, @Param("eventId") String eventId,
                          @Param("eventType") String eventType, @Param("status") String status,
                          @Param("location") String location, @Param("description") String description,
                          @Param("occurredAt") Instant occurredAt);

  int insertSignedNonce(@Param("appId") String appId, @Param("nonce") String nonce,
                        @Param("requestTimestamp") long requestTimestamp);

  int insertOutbox(@Param("shipmentId") Long shipmentId, @Param("eventId") String eventId,
                   @Param("callbackUrl") String callbackUrl, @Param("payload") String payload,
                   @Param("maxAttempts") int maxAttempts);

  Long outboxIdByEventId(String eventId);

  List<Map<String, Object>> listOutbox(@Param("status") String status, @Param("limit") int limit);

  List<Map<String, Object>> outboxEvents(Long outboxId);

  Map<String, Object> outboxById(Long id);

  Long lockOutbox(Long id);

  int replayOutbox(@Param("id") Long id, @Param("maxAttempts") int maxAttempts);

  List<Long> dueOutboxIds(@Param("now") Instant now, @Param("limit") int limit);

  int claimOutbox(@Param("id") Long id, @Param("now") Instant now,
                  @Param("leaseUntil") Instant leaseUntil, @Param("owner") String owner);

  int publishOutbox(@Param("id") Long id, @Param("owner") String owner);

  int failOutbox(@Param("id") Long id, @Param("owner") String owner,
                 @Param("status") String status, @Param("nextAttempt") Instant nextAttempt,
                 @Param("message") String message);

  int insertOutboxEvent(@Param("outboxId") Long outboxId, @Param("eventType") String eventType,
                        @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                        @Param("operatedBy") String operatedBy, @Param("reason") String reason);

  long countOutboxByStatus(String status);
}
