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
    bind(caseId, orderId, orderNumber, carrier, tracking, false);
  }

  public void bindReplacement(String caseId, Long orderId, String orderNumber, String carrier, String tracking) {
    bind(caseId, orderId, orderNumber, carrier, tracking, true);
  }

  private void bind(String caseId, Long orderId, String orderNumber, String carrier, String tracking,
                     boolean replacement) {
    String normalized = normalizeCarrier(carrier);
    if (shipments.parcelExists(normalized, tracking) != 0) throw alreadyBound();
    String reference = (replacement ? "CFL-EXCHANGE-" : "CFL-RETURN-") + caseId;
    try {
      shipments.prepareAfterSale(orderId, caseId, orderNumber, reference, normalized, tracking, replacement);
    } catch (DuplicateKeyException exception) {
      throw alreadyBound();
    }
  }

  public boolean matches(String caseId, String carrier, String tracking) {
    return matches(caseId, carrier, tracking, false);
  }

  public boolean matchesReplacement(String caseId, String carrier, String tracking) {
    return matches(caseId, carrier, tracking, true);
  }

  private boolean matches(String caseId, String carrier, String tracking, boolean replacement) {
    return shipments.byAfterSale(caseId, replacement).filter(shipment ->
      shipment.getCarrier().equals(normalizeCarrier(carrier)) && shipment.getTrackingNumber().equals(tracking)
    ).isPresent();
  }

  public LogisticsResponse tracking(String caseId) {
    return tracking(caseId, false);
  }

  public LogisticsResponse replacementTracking(String caseId) {
    return tracking(caseId, true);
  }

  private LogisticsResponse tracking(String caseId, boolean replacement) {
    return shipments.byAfterSale(caseId, replacement)
      .map(shipment -> LogisticsResponse.from(shipment, shipments.events(shipment.getId()))).orElse(null);
  }

  public boolean delivered(String caseId) {
    return shipments.byAfterSale(caseId, false)
      .filter(shipment -> "DELIVERED".equals(shipment.getStatus())).isPresent();
  }

  public boolean replacementRegistered(String caseId) {
    return shipments.byAfterSale(caseId, true)
      .filter(shipment -> shipment.getPlatformShipmentNo() != null).isPresent();
  }

  @Scheduled(fixedDelayString = "${commerceflow.logistics-platform.return-dispatch-delay:2s}")
  @SchedulerLock(name = "returnShipmentRegistration", lockAtLeastFor = "PT1S", lockAtMostFor = "PT2M")
  public void registerPending() {
    for (var shipment : shipments.pendingReturns()) {
      try {
        register(shipment);
        shipments.markReconciled(shipment.getId());
      } catch (RuntimeException exception) {
        shipments.markReconciliationFailed(shipment.getId(), "After-sales shipment registration pending retry");
        log.warn("Could not register after-sales shipment {}: {}", shipment.getMerchantShipmentNo(),
          exception.getMessage());
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
