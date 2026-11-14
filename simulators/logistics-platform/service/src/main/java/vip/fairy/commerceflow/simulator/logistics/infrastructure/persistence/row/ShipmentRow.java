package vip.fairy.commerceflow.simulator.logistics.infrastructure.persistence.row;

import java.sql.Timestamp;

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
public record ShipmentRow(Long id, String merchantShipmentNo, String platformShipmentNo,
                          String orderNumber, String carrier, String trackingNumber, String status,
                          String callbackUrl, Timestamp createdAt, Timestamp updatedAt) {
}
