package vip.fairy.commerceflow.simulator.logistics.infrastructure.persistence.row;

import java.sql.Timestamp;

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
public record CallbackOutboxRow(Long id, Long shipmentId, String eventId, String callbackUrl,
                                Object payload, String status, Integer attempts, Integer maxAttempts,
                                Timestamp nextAttemptAt, Timestamp leaseUntil, String claimedBy,
                                String lastError, Timestamp createdAt, Timestamp publishedAt,
                                Timestamp updatedAt) {
}
