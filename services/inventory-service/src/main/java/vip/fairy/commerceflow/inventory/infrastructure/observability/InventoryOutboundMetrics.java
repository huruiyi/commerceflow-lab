package vip.fairy.commerceflow.inventory.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryOutboundMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryOutboundMapper.Backlog;

@Component
public class InventoryOutboundMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(InventoryOutboundMetrics.class);

  private final InventoryOutboundMapper store;
  private final Counter publishFailures;
  private volatile Backlog backlog = new Backlog(0, 0);
  private volatile boolean refreshed;

  public InventoryOutboundMetrics(InventoryOutboundMapper store, MeterRegistry registry) {
    this.store = store;
    Gauge.builder("commerceflow.inventory.outbound.events.pending", this, value -> value.backlog.pending())
      .description("Number of outbound events without a recorded publishing acknowledgement")
      .register(registry);
    Gauge.builder("commerceflow.inventory.outbound.events.oldest.seconds",
        this, value -> value.backlog.oldestSeconds())
      .description("Age of the oldest unpublished outbound event measured from inventory posting")
      .register(registry);
    Gauge.builder("commerceflow.inventory.outbound.metrics.refresh.success", this, value -> value.refreshed ? 1 : 0)
      .description("Whether the most recent inventory outbound backlog query succeeded")
      .register(registry);
    publishFailures = Counter.builder("commerceflow.inventory.outbound.publish.failures")
      .description("Outbound event attempts that failed to send or record the Kafka acknowledgement")
      .register(registry);
  }

  @Scheduled(fixedDelayString = "${commerceflow.inventory.outbound-metrics-delay:5s}")
  public void refresh() {
    try {
      backlog = store.backlog();
      refreshed = true;
    } catch (RuntimeException failure) {
      refreshed = false;
      LOG.warn("Could not refresh inventory outbound metrics: {}", failure.getMessage());
    }
  }

  public void recordPublishFailure() {
    publishFailures.increment();
  }
}
