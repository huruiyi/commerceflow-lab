package vip.fairy.commerceflow.commerce.logistics.domain;

import java.time.Instant;

/**
 * 物流轨迹事件的只读持久化投影。
 */
public class ShipmentEventView {
  /** 商城物流事件自增主键。 */
  private Long id;
  /** 物流平台生成的全局事件标识，用于幂等去重。 */
  private String eventId;
  /** 轨迹事件类型：PROGRESS、EXCEPTION 或 RECOVERY。 */
  private String eventType;
  /** 事件声明的物流状态。 */
  private String status;
  /** 物流节点所在地点。 */
  private String location;
  /** 面向用户的轨迹描述。 */
  private String description;
  /** 事件在物流平台的发生时间。 */
  private Instant occurredAt;

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getStatus() {
    return status;
  }

  public String getLocation() {
    return location;
  }

  public String getDescription() {
    return description;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
