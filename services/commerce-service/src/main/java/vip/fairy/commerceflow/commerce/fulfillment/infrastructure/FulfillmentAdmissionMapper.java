package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.fulfillment.domain.FulfillmentOperation;

public interface FulfillmentAdmissionMapper {

  Boolean lockWriteAdmission();

  String writeOwner();

  int lockOrder(@Param("orderId") Long orderId);

  boolean hasOpenOperation(@Param("orderId") Long orderId);

  int reserveCommand(@Param("commandId") String commandId, @Param("orderId") Long orderId);

  Long lockedCommandOrder(@Param("commandId") String commandId);

  FulfillmentOperation findCommand(@Param("commandId") String commandId);
}
