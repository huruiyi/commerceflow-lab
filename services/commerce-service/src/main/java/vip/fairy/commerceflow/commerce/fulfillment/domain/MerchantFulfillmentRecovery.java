package vip.fairy.commerceflow.commerce.fulfillment.domain;

import java.time.Instant;

/**
 * 商家履约操作的人工恢复记录。commandId 对应原命令，recoveryId 用于恢复请求去重。
 * operatorId 来自管理员会话，step 与 previousError 保存重新入队前的执行状态。
 * reason、evidence 和 createdAt 记录本次恢复的依据与受理时间。
 */
public record MerchantFulfillmentRecovery(String commandId, String recoveryId, Long operatorId,
                                          MerchantFulfillmentJob.Step step, String previousError,
                                          String reason, String evidence, Instant createdAt) {
}
