package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.inventory.domain.InventoryReservation;
import vip.fairy.commerceflow.inventory.domain.ProductInventory;

public interface InventoryMapper {

  int reserve(@Param("productId") Long productId, @Param("quantity") int quantity);

  void insertReservation(@Param("orderId") Long orderId, @Param("productId") Long productId,
                         @Param("quantity") int quantity);

  List<InventoryReservation> lockByOrder(Long orderId);

  int commitReserved(@Param("productId") Long productId, @Param("quantity") int quantity);

  int commitReleased(@Param("productId") Long productId, @Param("quantity") int quantity);

  int release(@Param("productId") Long productId, @Param("quantity") int quantity);

  int refund(@Param("productId") Long productId, @Param("quantity") int quantity);

  int markCommitted(@Param("id") Long id, @Param("fromStatus") String fromStatus);

  int markReleased(Long id);

  int markRefunded(Long id);

  ProductInventory inventory(Long productId);

  void insertMovement(@Param("eventId") String eventId, @Param("orderId") Long orderId,
                      @Param("productId") Long productId, @Param("movementType") String movementType,
                      @Param("quantity") int quantity, @Param("inventory") ProductInventory inventory,
                      @Param("detail") String detail);

  Long catalogBusinessVersion(Long productId);

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
