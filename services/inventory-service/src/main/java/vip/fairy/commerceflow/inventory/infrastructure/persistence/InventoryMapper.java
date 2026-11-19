package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import vip.fairy.commerceflow.inventory.domain.StockAvailability;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.inventory.domain.InventoryReservation;
import vip.fairy.commerceflow.inventory.domain.ProductInventory;

public interface InventoryMapper {

  int reserve(@Param("productId") Long productId, @Param("quantity") int quantity);

  void insertReservation(@Param("orderId") Long orderId, @Param("productId") Long productId,
                         @Param("quantity") int quantity, @Param("warehouseId") long warehouseId);

  List<InventoryReservation> lockByOrder(Long orderId);

  List<InventoryReservation> activeReservations(Long orderId);

  List<StockAvailability> availability(@Param("ids") List<Long> ids);

  boolean hasMissingReservationLine(Long orderId);

  boolean hasCommittedReservations(Long orderId);

  int commitReserved(@Param("productId") Long productId, @Param("quantity") int quantity);

  int commitReleased(@Param("productId") Long productId, @Param("quantity") int quantity);

  int release(@Param("productId") Long productId, @Param("quantity") int quantity);

  int refund(@Param("productId") Long productId, @Param("quantity") int quantity);

  int markCommitted(@Param("id") Long id, @Param("fromStatus") String fromStatus);

  int markReleased(Long id);

  int markRefunded(Long id);

  ProductInventory inventory(Long productId);

  ProductInventory lockInventory(Long productId);

  void insertMovement(@Param("eventId") String eventId, @Param("orderId") Long orderId,
                      @Param("productId") Long productId, @Param("movementType") String movementType,
                      @Param("quantity") int quantity, @Param("inventory") ProductInventory inventory,
                      @Param("detail") String detail);

  void insertWarehouseMovement(@Param("eventId") String eventId, @Param("orderId") Long orderId,
                               @Param("productId") Long productId, @Param("movementType") String movementType,
                               @Param("quantity") int quantity, @Param("inventory") ProductInventory inventory,
                               @Param("detail") String detail, @Param("warehouseId") long warehouseId);

  Long catalogBusinessVersion(Long productId);

  void createCatalogSnapshot(
    vip.fairy.commerceflow.inventory.application.contract.v1.CatalogProductEventV1 event);

  int updateCatalogSnapshot(@Param("productId") Long productId,
                            @Param("merchantId") Long merchantId,
                            @Param("productName") String productName,
                            @Param("thumbnailUrl") String thumbnailUrl,
                            @Param("unitPrice") BigDecimal unitPrice,
                            @Param("lifecycleStatus") String lifecycleStatus,
                            @Param("businessVersion") long businessVersion);

  int advanceCatalogVersion(@Param("productId") Long productId,
                            @Param("businessVersion") long businessVersion);
}
