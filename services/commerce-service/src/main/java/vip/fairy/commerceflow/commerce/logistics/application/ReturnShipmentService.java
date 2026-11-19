package vip.fairy.commerceflow.commerce.logistics.application;

import java.util.Locale;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
import vip.fairy.commerceflow.commerce.logistics.domain.ShipmentRecordView;
import vip.fairy.commerceflow.commerce.logistics.infrastructure.ShipmentMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@Service
public class ReturnShipmentService {

  private static final Logger log = LoggerFactory.getLogger(ReturnShipmentService.class);
  private final ShipmentMapper shipments;
  private final LogisticsGatewayClient gateway;
  private final LogisticsNotificationService notifications;

  public ReturnShipmentService(ShipmentMapper shipments, LogisticsGatewayClient gateway,
                               LogisticsNotificationService notifications) {
    this.shipments = shipments;
    this.gateway = gateway;
    this.notifications = notifications;
  }

  public void bind(String caseId, Long orderId, String orderNumber, String carrier, String tracking) {
    String normalized = normalizeCarrier(carrier);
    if (shipments.parcelExists(normalized, tracking) != 0) throw alreadyBound();
    try {
      shipments.prepareReturn(orderId, caseId, orderNumber, "CFL-RETURN-" + caseId, normalized, tracking);
    } catch (DuplicateKeyException exception) {
      throw alreadyBound();
    }
  }

  public boolean matches(String caseId, String carrier, String tracking) {
    return shipments.byReturn(caseId).filter(shipment ->
      shipment.getCarrier().equals(normalizeCarrier(carrier)) && shipment.getTrackingNumber().equals(tracking)
    ).isPresent();
  }

  public LogisticsResponse tracking(String caseId) {
    return shipments.byReturn(caseId)
      .map(shipment -> LogisticsResponse.from(shipment, shipments.events(shipment.getId()))).orElse(null);
  }

  public void requireDelivered(String caseId) {
    if (shipments.byReturn(caseId).filter(shipment -> !"DELIVERED".equals(shipment.getStatus())).isPresent()) {
      throw new BusinessRuleException("Return shipment has not been delivered");
    }
  }

  @Scheduled(fixedDelayString = "${commerceflow.logistics-platform.return-dispatch-delay:2s}")
  @SchedulerLock(name = "returnShipmentRegistration", lockAtLeastFor = "PT1S", lockAtMostFor = "PT2M")
  public void registerPending() {
    for (var shipment : shipments.pendingReturns()) {
      try {
        register(shipment);
        shipments.markReconciled(shipment.getId());
      } catch (RuntimeException exception) {
        shipments.markReconciliationFailed(shipment.getId(), "Return shipment registration pending retry");
        log.warn("Could not register return shipment {}: {}", shipment.getMerchantShipmentNo(), exception.getMessage());
      }
    }
  }

  private void register(ShipmentRecordView shipment) {
    String reference = shipment.getMerchantShipmentNo();
    var result = gateway.findShipment(reference).orElseGet(() -> gateway.create(reference,
      shipment.getOrderNumber(), shipment.getCarrier(), shipment.getTrackingNumber()));
    if (!reference.equals(result.merchantShipmentNo()) || !shipment.getOrderNumber().equals(result.orderNumber())
      || !shipment.getCarrier().equals(result.carrier())
      || !shipment.getTrackingNumber().equals(result.trackingNumber())
      || result.platformShipmentNo() == null || result.events() == null || result.events().isEmpty()) {
      throw new IllegalStateException("Carrier returned an invalid registration");
    }
    if (notifications.reconcile(result).rejected() > 0) {
      throw new IllegalStateException("Carrier registration events did not match the return");
    }
  }

  private String normalizeCarrier(String carrier) {
    String normalized = carrier.strip().toUpperCase(Locale.ROOT);
    if (normalized.length() > 100) throw new IllegalArgumentException("Carrier exceeds 100 characters");
    return normalized;
  }

  private BusinessRuleException alreadyBound() {
    return new BusinessRuleException("Carrier tracking number is already bound to a shipment");
  }
}
