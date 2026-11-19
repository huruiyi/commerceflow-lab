package vip.fairy.commerceflow.catalog.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogEventOutboxMapper;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogEventOutboxMapper.Backlog;

@Component
public class CatalogEventDeliveryMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogEventDeliveryMetrics.class);
  private static final String PREFIX = "commerceflow.catalog.event.delivery.";

  private final CatalogEventOutboxMapper outbox;
  private volatile Backlog backlog = new Backlog(0, 0, 0);
  private volatile boolean refreshed;

  public CatalogEventDeliveryMetrics(CatalogEventOutboxMapper outbox, MeterRegistry registry) {
    this.outbox = outbox;
    Gauge.builder(PREFIX + "pending", this, metrics -> metrics.backlog.pending())
      .description("Number of catalog events awaiting delivery in NEW or RETRY")
      .register(registry);
    Gauge.builder(PREFIX + "oldest.seconds", this, metrics -> metrics.backlog.oldestSeconds())
      .description("Age of the oldest pending delivery measured from creation")
      .register(registry);
    Gauge.builder(PREFIX + "dead", this, metrics -> metrics.backlog.dead())
      .description("Number of catalog events in DEAD state")
      .register(registry);
    Gauge.builder(PREFIX + "metrics.refresh.success", this, metrics -> metrics.refreshed ? 1 : 0)
      .description("Whether the latest delivery backlog query succeeded")
      .register(registry);
  }

  @Scheduled(fixedDelayString = "${commerceflow.catalog-event-delivery.metrics-delay:5s}")
  public void refresh() {
    try {
      backlog = outbox.backlog();
      refreshed = true;
    } catch (RuntimeException failure) {
      refreshed = false;
      LOG.warn("Could not refresh delivery metrics: {}", failure.getMessage());
    }
  }
}
