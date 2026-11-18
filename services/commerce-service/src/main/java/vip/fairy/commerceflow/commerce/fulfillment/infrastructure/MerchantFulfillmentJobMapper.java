package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentRecovery;

public interface MerchantFulfillmentJobMapper {

  int insert(MerchantFulfillmentJob job);

  MerchantFulfillmentJob findAccepted(MerchantFulfillmentJob job);

  MerchantFulfillmentJob find(@Param("commandId") String commandId);

  MerchantFulfillmentJob lockForRecovery(@Param("commandId") String commandId);

  List<MerchantFulfillmentJob> reviewable(@Param("limit") int limit);

  MerchantFulfillmentRecovery findRecovery(@Param("commandId") String commandId,
                                           @Param("recoveryId") String recoveryId);

  List<MerchantFulfillmentRecovery> recoveryHistory(@Param("commandId") String commandId);

  int requeueReview(@Param("commandId") String commandId);

  int insertRecovery(MerchantFulfillmentRecovery recovery);

  List<String> dueCommands();

  int claim(@Param("commandId") String commandId, @Param("owner") String owner,
            @Param("immediate") boolean immediate);

  int advanceToOrder(@Param("commandId") String commandId, @Param("owner") String owner);

  int finish(@Param("commandId") String commandId, @Param("owner") String owner,
             @Param("status") String status, @Param("error") String error);

  int retry(@Param("commandId") String commandId, @Param("owner") String owner, @Param("error") String error);
}
