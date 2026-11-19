package vip.fairy.commerceflow.commerce.logistics.application.contract;

import java.util.Optional;
import org.apache.ibatis.annotations.AutomapConstructor;

public interface LogisticsFulfillmentV1 {

  Receipt execute(Command command);

  Optional<Receipt> findReceipt(String commandId);

  boolean hasShipment(Long orderId);

  /** 命令标识由履约步骤派生。订单号用于建单，前置版本用于条件修改，承运商与运单号为原始受理参数。 */
  record Command(String commandId, Operation operation, Long orderId, String orderNumber,
                 String carrier, String trackingNumber, Long expectedVersion, String packageId) {

    @AutomapConstructor
    public Command {
    }

    public Command(String commandId, Operation operation, Long orderId, String orderNumber,
                   String carrier, String trackingNumber, Long expectedVersion) {
      this(commandId, operation, orderId, orderNumber, carrier, trackingNumber, expectedVersion, null);
    }
  }

  enum Operation {
    ENSURE_SHIPMENT, UPDATE_DETAILS
  }

  /** 原命令确认成功时的渠道运单标识及版本。后续运单修改不会改变此回执。 */
  record Receipt(String commandId, String platformShipmentNo, long shipmentVersion) {
  }
}
