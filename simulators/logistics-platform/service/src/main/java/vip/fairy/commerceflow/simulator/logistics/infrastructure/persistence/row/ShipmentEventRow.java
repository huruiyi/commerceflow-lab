package vip.fairy.commerceflow.simulator.logistics.infrastructure.persistence.row;

import java.sql.Timestamp;

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
public record ShipmentEventRow(String eventId, String eventType, String status, String location,
                               String description, Timestamp occurredAt) {
}
