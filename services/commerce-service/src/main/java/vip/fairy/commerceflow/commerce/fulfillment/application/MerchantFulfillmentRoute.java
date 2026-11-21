package vip.fairy.commerceflow.commerce.fulfillment.application;

import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentService.CommandResult;
import vip.fairy.commerceflow.commerce.fulfillment.application.contract.FulfillmentCommandV1;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;

public interface MerchantFulfillmentRoute {

  CommandResult submit(FulfillmentCommandV1 command);

  CommandResult commandStatus(Long merchantId, String commandId);

  ShipmentJob shipment(Long merchantId, Long orderId);
}
