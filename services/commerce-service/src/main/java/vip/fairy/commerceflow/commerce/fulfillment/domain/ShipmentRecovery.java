package vip.fairy.commerceflow.commerce.fulfillment.domain;

import java.time.Instant;

/**
 * 发货恢复的审计快照。recoveryId 用于恢复请求去重，commandId 对应原发货操作。
 * operatorId 来自登录身份，step 与 previousError 记录重试前的事实。
 * reason、evidence 和 createdAt 记录重新入队的依据与时间。
 */
public record ShipmentRecovery(Long orderId, String recoveryId, String commandId, Long operatorId,
                               ShipmentJob.Step step, String previousError, String reason, String evidence,
                               Instant createdAt) {
}
