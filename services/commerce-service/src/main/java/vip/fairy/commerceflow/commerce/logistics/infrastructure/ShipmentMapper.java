package vip.fairy.commerceflow.commerce.logistics.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import vip.fairy.commerceflow.commerce.logistics.domain.ShipmentEventView;
import vip.fairy.commerceflow.commerce.logistics.domain.ShipmentRecordView;
import org.apache.ibatis.annotations.Param;

public interface ShipmentMapper {

  void prepare(
    @Param("orderId") Long orderId,
    @Param("merchantNo") String merchantNo,
    @Param("carrier") String carrier,
    @Param("tracking") String tracking
  );

  Optional<ShipmentRecordView> byOrder(Long orderId);

  Optional<ShipmentRecordView> lockByMerchantNo(String merchantNo);

  List<ShipmentRecordView> reconciliationCandidates(
    @Param("updatedBefore") Instant updatedBefore,
    @Param("limit") int limit
  );

  int attach(
    @Param("id") Long id,
    @Param("platformNo") String platformNo,
    @Param("carrier") String carrier,
    @Param("tracking") String tracking,
    @Param("status") String status
  );

  int updateDetails(
    @Param("orderId") Long orderId,
    @Param("carrier") String carrier,
    @Param("tracking") String tracking
  );

  int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("occurredAt") Instant occurredAt);

  int insertEvent(
    @Param("shipmentId") Long shipmentId,
    @Param("eventId") String eventId,
    @Param("eventType") String eventType,
    @Param("status") String status,
    @Param("location") String location,
    @Param("description") String description,
    @Param("occurredAt") Instant occurredAt
  );

  List<ShipmentEventView> events(Long shipmentId);

  void insertNotification(
    @Param("shipmentId") Long shipmentId,
    @Param("notificationId") String notificationId,
    @Param("processingStatus") String processingStatus,
    @Param("digest") String digest,
    @Param("reason") String reason
  );

  int notificationCount(String notificationId);

  int markReconciled(Long id);

  int markReconciliationFailed(@Param("id") Long id, @Param("error") String error);
}
