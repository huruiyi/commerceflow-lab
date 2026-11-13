package vip.fairy.commerceflow.simulator.logistics;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

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

  ShipmentRow findShipmentByMerchantNo(String merchantShipmentNo);

  ShipmentRow lockShipmentByMerchantNo(String merchantShipmentNo);

  ShipmentRow lockShipmentById(Long id);

  ShipmentRow shipmentById(Long id);

  List<ShipmentEventRow> shipmentEvents(Long shipmentId);

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

  List<CallbackOutboxRow> listOutbox(@Param("status") String status, @Param("limit") int limit);

  List<CallbackOutboxEventRow> outboxEvents(Long outboxId);

  CallbackOutboxRow outboxById(Long id);

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

  /**
   * 运单主记录持久化投影。
   *
   * @param id 物流平台内部自增主键
   * @param merchantShipmentNo 商城侧稳定运单号
   * @param platformShipmentNo 模拟物流平台运单号
   * @param orderNumber 商城订单号
   * @param carrier 承运商标识
   * @param trackingNumber 承运商查询号
   * @param status 当前物流状态
   * @param callbackUrl 商城物流通知回调地址
   * @param createdAt 运单创建时间
   * @param updatedAt 运单最后变更时间
   */
  record ShipmentRow(Long id, String merchantShipmentNo, String platformShipmentNo,
                     String orderNumber, String carrier, String trackingNumber, String status,
                     String callbackUrl, Timestamp createdAt, Timestamp updatedAt) {
  }

  /**
   * 运单轨迹事件持久化投影。
   *
   * @param eventId 去重用的事件标识
   * @param eventType 轨迹事件类型
   * @param status 事件对应的物流状态
   * @param location 物流节点位置
   * @param description 轨迹描述
   * @param occurredAt 事件在物流平台发生的时间
   */
  record ShipmentEventRow(String eventId, String eventType, String status, String location,
                          String description, Timestamp occurredAt) {
  }

  /**
   * 物流回调 Outbox 持久化投影。
   *
   * @param id Outbox 自增主键
   * @param shipmentId 关联运单主键
   * @param eventId 关联运单事件标识
   * @param callbackUrl 回调目标地址
   * @param payload JSON 回调请求体
   * @param status NEW、PROCESSING、RETRY、PUBLISHED 或 DEAD
   * @param attempts 已领取投递次数
   * @param maxAttempts 进入 DEAD 前的最大投递次数
   * @param nextAttemptAt 下次可领取时间
   * @param leaseUntil PROCESSING 租约到期时间
   * @param claimedBy 当前投递者唯一标识
   * @param lastError 最近一次投递失败摘要
   * @param createdAt Outbox 创建时间
   * @param publishedAt 成功投递时间
   * @param updatedAt Outbox 最后变更时间
   */
  record CallbackOutboxRow(Long id, Long shipmentId, String eventId, String callbackUrl,
                           Object payload, String status, Integer attempts, Integer maxAttempts,
                           Timestamp nextAttemptAt, Timestamp leaseUntil, String claimedBy,
                           String lastError, Timestamp createdAt, Timestamp publishedAt,
                           Timestamp updatedAt) {
  }

  /**
   * Outbox 操作审计事件持久化投影。
   *
   * @param id 审计事件自增主键
   * @param eventType 审计事件类型
   * @param fromStatus 操作前 Outbox 状态
   * @param toStatus 操作后 Outbox 状态
   * @param operatedBy 系统或人工操作者
   * @param reason 状态变更原因
   * @param createdAt 审计事件创建时间
   */
  record CallbackOutboxEventRow(Long id, String eventType, String fromStatus,
                                String toStatus, String operatedBy, String reason,
                                Timestamp createdAt) {
  }
}
