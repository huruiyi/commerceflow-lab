package vip.fairy.commerceflow.commerce.fulfillment.application;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentRecoveryService.Review;
import vip.fairy.commerceflow.commerce.fulfillment.application.ShipmentRecoveryService.ShipmentReview;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentRecovery;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentRecovery;

@Service
@ConditionalOnProperty(name = "commerceflow.fulfillment.remote.enabled", havingValue = "false", matchIfMissing = true)
class LocalFulfillmentRecovery implements FulfillmentRecoveryRoute {

  private final ShipmentRecoveryService shipments;
  private final MerchantFulfillmentRecoveryService operations;

  LocalFulfillmentRecovery(ShipmentRecoveryService shipments, MerchantFulfillmentRecoveryService operations) {
    this.shipments = shipments;
    this.operations = operations;
  }

  @Override
  public List<ShipmentJob> shipments(int limit) {
    return shipments.reviewable(limit);
  }

  @Override
  public ShipmentReview shipment(Long orderId) {
    return shipments.detail(orderId);
  }

  @Override
  public ShipmentRecovery retryShipment(Long orderId, String commandId, Retry retry) {
    return shipments.retry(orderId, retry.recoveryId(), commandId,
      retry.operatorId(), retry.reason(), retry.evidence());
  }

  @Override
  public List<MerchantFulfillmentJob> merchantOperations(int limit) {
    return operations.reviewable(limit);
  }

  @Override
  public Review merchantOperation(String commandId) {
    return operations.detail(commandId);
  }

  @Override
  public MerchantFulfillmentRecovery retryMerchantOperation(String commandId, Retry retry) {
    return operations.retry(commandId, retry.recoveryId(), retry.operatorId(), retry.reason(), retry.evidence());
  }
}
