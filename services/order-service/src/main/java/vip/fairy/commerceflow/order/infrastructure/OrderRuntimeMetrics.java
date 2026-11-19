package vip.fairy.commerceflow.order.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderMonitoringMapper;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderMonitoringMapper.Backlog;

@Component
public class OrderRuntimeMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(OrderRuntimeMetrics.class);

  private final OrderMonitoringMapper store;
  private volatile Backlog backlog = new Backlog(0, 0, 0);
  private volatile boolean refreshed;

  public OrderRuntimeMetrics(OrderMonitoringMapper store, MeterRegistry registry) {
    this.store = store;
    Gauge.builder("commerceflow.order.shipment.intents.pending", this, value -> value.backlog.shipmentIntents())
      .description("Confirmed orders with an unfinished shipment intent")
      .register(registry);
    Gauge.builder("commerceflow.order.shipment.intents.oldest.seconds",
        this, value -> value.backlog.oldestShipmentIntentSeconds())
      .description("Longest inactivity of a confirmed order with a shipment intent, measured from updated_at")
      .register(registry);
    Gauge.builder("commerceflow.order.cancellation.recovery.required",
        this, value -> value.backlog.cancellationRecoveryRequired())
      .description("Order cancellation protocol processes requiring reconciliation, compensation or manual review")
      .register(registry);
    Gauge.builder("commerceflow.order.metrics.refresh.success", this, value -> value.refreshed ? 1 : 0)
      .description("Whether the latest order backlog query succeeded")
      .register(registry);
  }

  @Scheduled(fixedDelayString = "${commerceflow.order.metrics-delay:5s}")
  public void refresh() {
    try {
      backlog = store.backlog();
      refreshed = true;
    } catch (RuntimeException failure) {
      refreshed = false;
      LOG.warn("Could not refresh order metrics: {}", failure.getMessage());
    }
  }
}
