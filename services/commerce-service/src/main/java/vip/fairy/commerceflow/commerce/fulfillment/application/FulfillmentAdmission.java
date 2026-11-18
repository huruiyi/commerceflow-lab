package vip.fairy.commerceflow.commerce.fulfillment.application;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.fulfillment.domain.FulfillmentOperation;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.FulfillmentAdmissionMapper;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.MerchantFulfillmentJobMapper;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.ShipmentJobMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;

@Service
public class FulfillmentAdmission {

  private final FulfillmentAdmissionMapper admission;
  private final ShipmentJobMapper shipments;
  private final MerchantFulfillmentJobMapper merchantJobs;

  public FulfillmentAdmission(FulfillmentAdmissionMapper admission, ShipmentJobMapper shipments,
                              MerchantFulfillmentJobMapper merchantJobs) {
    this.admission = admission;
    this.shipments = shipments;
    this.merchantJobs = merchantJobs;
  }

  @Transactional
  public Accepted<ShipmentJob> shipment(ShipmentJob request, boolean explicitCommand) {
    Boolean frozen = admission.lockWriteAdmission();
    if (Boolean.FALSE.equals(frozen)) admission.lockOrder(request.orderId());
    ShipmentJob prior = shipments.findSlot(request.orderId());
    if (prior != null) {
      requireMatch(request.commandId(), prior.commandId(), explicitCommand,
        request.merchantId(), prior.merchantId(), request.carrier(), prior.carrier(),
        request.trackingNumber(), prior.trackingNumber());
      return new Accepted<>(prior, false);
    }
    reserve(request.orderId(), request.commandId(), frozen);
    shipments.insert(request);
    return new Accepted<>(request, true);
  }

  @Transactional
  public Accepted<MerchantFulfillmentJob> merchant(MerchantFulfillmentJob request, boolean explicitCommand) {
    Boolean frozen = admission.lockWriteAdmission();
    if (Boolean.FALSE.equals(frozen)) admission.lockOrder(request.orderId());
    MerchantFulfillmentJob prior = merchantJobs.findAccepted(request);
    if (prior != null) {
      requireMatch(request.commandId(), prior.commandId(), explicitCommand,
        request.merchantId(), prior.merchantId(), request.carrier(), prior.carrier(),
        request.trackingNumber(), prior.trackingNumber());
      return new Accepted<>(prior, false);
    }
    reserve(request.orderId(), request.commandId(), frozen);
    merchantJobs.insert(request);
    return new Accepted<>(request, true);
  }

  public FulfillmentOperation find(String commandId) {
    return admission.findCommand(commandId);
  }

  private void reserve(Long orderId, String commandId, Boolean frozen) {
    if (!Boolean.FALSE.equals(frozen)) {
      throw new ExternalServiceUnavailableException("FULFILLMENT_WRITE_FROZEN",
        "New fulfillment commands are temporarily frozen", null);
    }
    if (admission.hasOpenOperation(orderId)) {
      throw new BusinessRuleException("Another fulfillment operation is pending or requires review");
    }
    admission.reserveCommand(commandId, orderId);
    if (!orderId.equals(admission.lockedCommandOrder(commandId)) || admission.findCommand(commandId) != null) {
      throw new BusinessRuleException("Fulfillment command ID was already used");
    }
  }

  private void requireMatch(String command, String priorCommand, boolean explicitCommand, Long merchant,
                            Long priorMerchant, String carrier, String priorCarrier, String tracking,
                            String priorTracking) {
    if ((explicitCommand && !command.equals(priorCommand)) || !merchant.equals(priorMerchant)
        || !Objects.equals(carrier, priorCarrier) || !Objects.equals(tracking, priorTracking)) {
      throw new BusinessRuleException("Parameters or command ID differ from the accepted fulfillment operation");
    }
  }

  public record Accepted<T>(T job, boolean created) {
  }
}
