package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.inventory.application.contract.v1.DeliveredInventoryEvidenceV1.Line;

public interface DeliveredInventoryReconciliationMapper {

  int claim(@Param("orderId") long orderId, @Param("fingerprint") String fingerprint,
            @Param("actor") String actor);

  String fingerprint(long orderId);

  int claimAvailable(@Param("productId") long productId, @Param("quantity") int quantity,
                     @Param("expectedAvailable") int expectedAvailable);

  int orderMovementCount(@Param("orderId") long orderId, @Param("productId") long productId);

  void insertCommitted(@Param("orderId") long orderId, @Param("productId") long productId,
                       @Param("quantity") int quantity);

  Long bookedMovement(@Param("movementId") long movementId, @Param("orderId") long orderId,
                      @Param("productId") long productId, @Param("quantity") int quantity);

  Long movementId(String eventId);

  void record(@Param("orderId") long orderId, @Param("line") Line line,
              @Param("movementId") long movementId, @Param("disposition") String disposition);

  List<Long> movements(long orderId);
}
