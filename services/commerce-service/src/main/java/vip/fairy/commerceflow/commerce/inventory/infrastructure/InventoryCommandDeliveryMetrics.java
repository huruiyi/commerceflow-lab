package vip.fairy.commerceflow.commerce.inventory.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper.Backlog;

@Component
public class InventoryCommandDeliveryMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(InventoryCommandDeliveryMetrics.class);

  private final InventoryCommandDeliveryMapper delivery;
  private volatile Backlog backlog = new Backlog(0, 0, 0);
  private volatile boolean refreshed;

  public InventoryCommandDeliveryMetrics(InventoryCommandDeliveryMapper delivery, MeterRegistry registry) {
    this.delivery = delivery;
    Gauge.builder("commerceflow.inventory.command.delivery.reconciliation.required",
        this, value -> value.backlog.reconciliationRequired())
      .description("Inventory commands awaiting manual reconciliation")
      .register(registry);
    Gauge.builder("commerceflow.inventory.command.delivery.pending", this, value -> value.backlog.pending())
      .description("Inventory commands in NEW, IN_FLIGHT or RETRY")
      .register(registry);
    Gauge.builder("commerceflow.inventory.command.delivery.oldest.seconds",
        this, value -> value.backlog.oldestSeconds())
      .description("Age of the oldest NEW, IN_FLIGHT or RETRY inventory command measured from creation")
      .register(registry);
    Gauge.builder("commerceflow.inventory.command.delivery.metrics.refresh.success",
        this, value -> value.refreshed ? 1 : 0)
      .description("Whether the most recent inventory command delivery backlog query succeeded")
      .register(registry);
  }

  @Scheduled(fixedDelayString = "${commerceflow.inventory.delivery.metrics-delay:5s}")
  public void refresh() {
    try {
      backlog = delivery.backlog();
      refreshed = true;
    } catch (RuntimeException failure) {
      refreshed = false;
      LOG.warn("Could not refresh inventory command delivery metrics: {}", failure.getMessage());
    }
  }
}
