package vip.fairy.commerceflow.commerce.ordering.domain;

import java.time.Instant;

/**
 * 订单生命周期事件投影，用于审计状态迁移及其触发来源。
 */
public class OrderEventView {
  /**
   * 订单事件内部主键。
   */
  private Long id;
  /**
   * 执行操作的用户内部主键；系统任务触发时可以为空。
   */
  private Long actorId;
  /**
   * 稳定的订单事件类型代码。
   */
  private String eventType;
  /**
   * 状态迁移前的订单状态或业务起点。
   */
  private String fromStatus;
  /**
   * 状态迁移后的订单状态。
   */
  private String toStatus;
  /**
   * 事件原因、物流变更或其他可读审计详情。
   */
  private String detail;
  /**
   * 触发来源，例如 USER、SCHEDULED_TASK 或物流回调。
   */
  private String triggerSource;
  /**
   * 关联定时任务内部主键；非任务触发时为空。
   */
  private Long scheduledTaskId;
  /**
   * 事件发生时间。
   */
  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public Long getActorId() {
    return actorId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getFromStatus() {
    return fromStatus;
  }

  public String getToStatus() {
    return toStatus;
  }

  public String getDetail() {
    return detail;
  }

  public String getTriggerSource() {
    return triggerSource;
  }

  public Long getScheduledTaskId() {
    return scheduledTaskId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
