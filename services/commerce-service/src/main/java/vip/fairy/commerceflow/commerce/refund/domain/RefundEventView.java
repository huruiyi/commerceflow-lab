package vip.fairy.commerceflow.commerce.refund.domain;

import java.time.Instant;

/**
 * 退款状态变更的只读事件投影。
 */
public class RefundEventView {

  /** 退款事件自增主键。 */
  private Long id;
  /** 退款事件类型，例如 REFUND_SUCCEEDED。 */
  private String eventType;
  /** 状态迁移前的退款状态。 */
  private String fromStatus;
  /** 状态迁移后的退款状态。 */
  private String toStatus;
  /** 事件原因或渠道处理摘要。 */
  private String detail;
  /** 事件入库时间。 */
  private Instant createdAt;

  public Long getId() {
    return id;
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

  public Instant getCreatedAt() {
    return createdAt;
  }
}
