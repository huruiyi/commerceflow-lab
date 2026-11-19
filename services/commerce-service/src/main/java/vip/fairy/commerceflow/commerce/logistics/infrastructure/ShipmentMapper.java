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
    @Param("packageId") String packageId,
    @Param("merchantNo") String merchantNo,
    @Param("carrier") String carrier,
    @Param("tracking") String tracking
  );

  Optional<ShipmentRecordView> byOrder(Long orderId);
  List<ShipmentRecordView> packages(Long orderId);
  List<PackageDelivery> pendingPackageDeliveries();
  void checkPackageDelivery(@Param("orderId") Long orderId, @Param("reported") boolean reported);

  Optional<ShipmentRecordView> byReturn(String afterSaleId);

  int parcelExists(@Param("carrier") String carrier, @Param("tracking") String tracking);

  void prepareReturn(
    @Param("orderId") Long orderId,
    @Param("afterSaleId") String afterSaleId,
    @Param("orderNumber") String orderNumber,
    @Param("merchantNo") String merchantNo,
    @Param("carrier") String carrier,
    @Param("tracking") String tracking
  );

  List<ShipmentRecordView> pendingReturns();

  int attachReturn(@Param("id") Long id, @Param("platformNo") String platformNo);

  int updateReturnStatus(@Param("id") Long id, @Param("status") String status,
                         @Param("occurredAt") Instant occurredAt);

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
    @Param("tracking") String tracking,
    @Param("version") long version
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

  /** 最后一只包裹送达后可核对的订单。 */
  record PackageDelivery(Long orderId, Instant deliveredAt) {
  }
}
