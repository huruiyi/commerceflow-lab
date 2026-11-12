package vip.fairy.commerceflow.commerce.logistics.application;

import java.time.Instant;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import vip.fairy.commerceflow.commerce.logistics.infrastructure.LogisticsReconciliationProperties;
import vip.fairy.commerceflow.commerce.logistics.infrastructure.ShipmentMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LogisticsReconciliationJob {
  private static final Logger log = LoggerFactory.getLogger(LogisticsReconciliationJob.class);
  private final ShipmentMapper shipments;
  private final LogisticsGatewayClient gateway;
  private final LogisticsNotificationService notifications;
  private final LogisticsReconciliationProperties properties;
  private final Counter checked;
  private final Counter recovered;
  private final Counter failures;

  public LogisticsReconciliationJob(ShipmentMapper shipments, LogisticsGatewayClient gateway,
                                    LogisticsNotificationService notifications, LogisticsReconciliationProperties properties,
                                    MeterRegistry registry) {
    this.shipments = shipments;
    this.gateway = gateway;
    this.notifications = notifications;
    this.properties = properties;
    this.checked = registry.counter("commerceflow.logistics.reconciliation", "result", "checked");
    this.recovered = registry.counter("commerceflow.logistics.reconciliation", "result", "recovered");
    this.failures = registry.counter("commerceflow.logistics.reconciliation", "result", "failed");
  }

  @Scheduled(initialDelayString = "${commerceflow.logistics-platform.reconciliation.initial-delay:30s}",
    fixedDelayString = "${commerceflow.logistics-platform.reconciliation.scan-interval:1m}")
  @SchedulerLock(name = "logisticsReconciliation", lockAtLeastFor = "PT1S", lockAtMostFor = "PT55S")
  public void reconcile() {
    if (!properties.isEnabled()) return;
    Instant threshold = Instant.now().minus(properties.getStaleAfter());
    for (var shipment : shipments.reconciliationCandidates(threshold, properties.getBatchSize())) {
      try {
        checked.increment();
        LogisticsGatewayClient.Result authoritative = gateway.query(shipment.getMerchantShipmentNo());
        var result = notifications.reconcile(authoritative);
        recovered.increment(result.processed());
        shipments.markReconciled(shipment.getId());
      } catch (Exception exception) {
        failures.increment();
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName()
          : exception.getMessage();
        if (message.length() > 1000) message = message.substring(0, 1000);
        shipments.markReconciliationFailed(shipment.getId(), message);
        log.warn("Logistics reconciliation failed for shipment {}", shipment.getMerchantShipmentNo());
      }
    }
  }
}
