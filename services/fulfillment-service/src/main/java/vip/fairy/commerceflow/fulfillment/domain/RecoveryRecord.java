package vip.fairy.commerceflow.fulfillment.domain;

import java.time.Instant;

/** 重新入队审计，关联原命令、恢复请求、操作员、失败步骤及原因证据。 */
public record RecoveryRecord(String commandId, String recoveryId, long operatorId,
                             String step, String previousError, String reason, String evidence, Instant createdAt) {
}
