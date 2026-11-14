package vip.fairy.commerceflow.simulator.logistics.infrastructure.persistence.row;

import java.sql.Timestamp;

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
public record CallbackOutboxEventRow(Long id, String eventType, String fromStatus,
                                     String toStatus, String operatedBy, String reason,
                                     Timestamp createdAt) {
}
