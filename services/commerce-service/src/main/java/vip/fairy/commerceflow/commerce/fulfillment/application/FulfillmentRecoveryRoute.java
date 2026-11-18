package vip.fairy.commerceflow.commerce.fulfillment.application;

import java.util.List;

import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentRecoveryService.Review;
import vip.fairy.commerceflow.commerce.fulfillment.application.ShipmentRecoveryService.ShipmentReview;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentRecovery;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentRecovery;

public interface FulfillmentRecoveryRoute {

  List<ShipmentJob> shipments(int limit);

  ShipmentReview shipment(Long orderId);

  ShipmentRecovery retryShipment(Long orderId, String commandId, Retry retry);

  List<MerchantFulfillmentJob> merchantOperations(int limit);

  Review merchantOperation(String commandId);

  MerchantFulfillmentRecovery retryMerchantOperation(String commandId, Retry retry);

  record Retry(String recoveryId, Long operatorId, String reason, String evidence) {
  }
}
