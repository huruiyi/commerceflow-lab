package lab.commerceflow.storefront.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lab.commerceflow.storefront.domain.ShipmentEventView;
import lab.commerceflow.storefront.domain.ShipmentRecordView;
import org.apache.ibatis.annotations.*;

public interface ShipmentMapper {
  @Insert("INSERT INTO shipment_records(order_id,merchant_shipment_no,carrier,tracking_number,status) VALUES(#{orderId},#{merchantNo},#{carrier},#{tracking},'REQUESTED') ON DUPLICATE KEY UPDATE carrier=VALUES(carrier),tracking_number=VALUES(tracking_number),updated_at=CURRENT_TIMESTAMP")
  void prepare(@Param("orderId")Long orderId,@Param("merchantNo")String merchantNo,@Param("carrier")String carrier,@Param("tracking")String tracking);
  @Select("SELECT * FROM shipment_records WHERE order_id=#{orderId}") Optional<ShipmentRecordView> byOrder(Long orderId);
  @Select("SELECT * FROM shipment_records WHERE merchant_shipment_no=#{merchantNo} FOR UPDATE") Optional<ShipmentRecordView> lockByMerchantNo(String merchantNo);
  @Select("SELECT * FROM shipment_records WHERE status<>'DELIVERED' AND COALESCE(last_reconciled_at,updated_at)<=#{updatedBefore} ORDER BY COALESCE(last_reconciled_at,updated_at),id LIMIT #{limit}")
  List<ShipmentRecordView> reconciliationCandidates(@Param("updatedBefore")Instant updatedBefore,@Param("limit")int limit);
  @Update("UPDATE shipment_records SET platform_shipment_no=#{platformNo},carrier=#{carrier},tracking_number=#{tracking},status=#{status},shipped_at=COALESCE(shipped_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
  int attach(@Param("id")Long id,@Param("platformNo")String platformNo,@Param("carrier")String carrier,@Param("tracking")String tracking,@Param("status")String status);
  @Update("UPDATE shipment_records SET carrier=#{carrier},tracking_number=#{tracking},updated_at=CURRENT_TIMESTAMP WHERE order_id=#{orderId}") int updateDetails(@Param("orderId")Long orderId,@Param("carrier")String carrier,@Param("tracking")String tracking);
  @Update("UPDATE shipment_records SET status=#{status},delivered_at=CASE WHEN #{status}='DELIVERED' THEN COALESCE(delivered_at,#{occurredAt}) ELSE delivered_at END,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
  int updateStatus(@Param("id")Long id,@Param("status")String status,@Param("occurredAt")Instant occurredAt);
  @Insert("INSERT IGNORE INTO shipment_events(shipment_id,event_id,event_type,status,location,description,occurred_at) VALUES(#{shipmentId},#{eventId},#{eventType},#{status},#{location},#{description},#{occurredAt})")
  int insertEvent(@Param("shipmentId")Long shipmentId,@Param("eventId")String eventId,@Param("eventType")String eventType,@Param("status")String status,@Param("location")String location,@Param("description")String description,@Param("occurredAt")Instant occurredAt);
  @Select("SELECT id,event_id,event_type,status,location,description,occurred_at FROM shipment_events WHERE shipment_id=#{shipmentId} ORDER BY occurred_at,id") List<ShipmentEventView> events(Long shipmentId);
  @Insert("INSERT INTO shipment_notifications(shipment_id,notification_id,signature_valid,processing_status,payload_digest,failure_reason,processed_at) VALUES(#{shipmentId},#{notificationId},TRUE,#{processingStatus},#{digest},#{reason},CURRENT_TIMESTAMP)")
  void insertNotification(@Param("shipmentId")Long shipmentId,@Param("notificationId")String notificationId,@Param("processingStatus")String processingStatus,@Param("digest")String digest,@Param("reason")String reason);
  @Select("SELECT COUNT(*) FROM shipment_notifications WHERE notification_id=#{notificationId}") int notificationCount(String notificationId);
  @Update("UPDATE shipment_records SET last_reconciled_at=CURRENT_TIMESTAMP(6),reconciliation_error=NULL WHERE id=#{id}") int markReconciled(Long id);
  @Update("UPDATE shipment_records SET last_reconciled_at=CURRENT_TIMESTAMP(6),reconciliation_error=#{error} WHERE id=#{id}") int markReconciliationFailed(@Param("id")Long id,@Param("error")String error);
}
