package lab.commerceflow.logistics;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AutomaticShipmentProgressJob {
  private static final Logger log = LoggerFactory.getLogger(AutomaticShipmentProgressJob.class);
  private final ShipmentService shipments;
  private final LogisticsAutomationProperties properties;

  public AutomaticShipmentProgressJob(ShipmentService shipments,
      LogisticsAutomationProperties properties) {
    this.shipments = shipments;
    this.properties = properties;
  }

  @Scheduled(initialDelayString = "${logistics-platform.automation.initial-delay:10s}",
      fixedDelayString = "${logistics-platform.automation.scan-interval:5s}")
  public void advanceDueShipments() {
    if (!properties.isEnabled()) return;
    Instant updatedBefore = Instant.now().minus(properties.getStepInterval());
    for (Long shipmentId : shipments.automaticCandidates(updatedBefore, properties.getBatchSize())) {
      try {
        shipments.advanceById(shipmentId);
      } catch (Exception exception) {
        log.warn("Automatic shipment advance failed for shipment {}", shipmentId, exception);
      }
    }
  }
}
