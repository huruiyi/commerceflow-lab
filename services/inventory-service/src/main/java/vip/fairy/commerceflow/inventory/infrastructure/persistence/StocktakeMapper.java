package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import java.time.Instant;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.inventory.application.contract.v1.StocktakeV1.Receipt;

public interface StocktakeMapper {

  Movement lastMovement(long productId);

  Long orderReservation(long orderId);

  Long orderMovement(long orderId);

  int claim(@Param("reference") String reference, @Param("fingerprint") String fingerprint,
            @Param("auditData") String auditData, @Param("adjustment") int adjustment);

  String fingerprint(String reference);

  void setAvailable(@Param("productId") long productId, @Param("counted") int counted);

  void link(@Param("orderId") long orderId, @Param("reference") String reference);

  void attachMovement(@Param("reference") String reference, @Param("movementId") long movementId);

  Receipt receipt(String reference);

  record Movement(long id, Instant createdAt) {
  }
}
