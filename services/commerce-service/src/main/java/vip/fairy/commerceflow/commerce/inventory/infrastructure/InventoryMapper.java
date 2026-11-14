package vip.fairy.commerceflow.commerce.inventory.infrastructure;

import vip.fairy.commerceflow.commerce.inventory.domain.ProductInventory;

import java.util.List;

import vip.fairy.commerceflow.commerce.inventory.domain.InventoryReservation;
import org.apache.ibatis.annotations.Param;

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


  Long customerId(Long orderId);
}
