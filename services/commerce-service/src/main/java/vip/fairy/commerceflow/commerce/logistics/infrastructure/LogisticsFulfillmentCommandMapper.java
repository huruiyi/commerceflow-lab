package vip.fairy.commerceflow.commerce.logistics.infrastructure;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Command;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Receipt;

public interface LogisticsFulfillmentCommandMapper {

  int register(Command command);

  Command find(@Param("commandId") String commandId);

  Receipt findReceipt(@Param("commandId") String commandId);

  int claim(@Param("commandId") String commandId, @Param("owner") String owner);

  int complete(@Param("receipt") Receipt receipt, @Param("owner") String owner);

  int release(@Param("commandId") String commandId, @Param("owner") String owner);
}
