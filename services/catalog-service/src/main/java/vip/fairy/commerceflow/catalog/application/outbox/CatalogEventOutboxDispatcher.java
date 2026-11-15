package vip.fairy.commerceflow.catalog.application.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.catalog.domain.CatalogEventOutbox;
import vip.fairy.commerceflow.catalog.infrastructure.delivery.CatalogEventDeliveryProperties;
import vip.fairy.commerceflow.catalog.infrastructure.delivery.InventoryCatalogEventClient;
import vip.fairy.commerceflow.catalog.infrastructure.persistence.CatalogEventOutboxMapper;

import java.time.Instant;

@Component
public class CatalogEventOutboxDispatcher {

  private final CatalogEventOutboxMapper outbox;
  private final InventoryCatalogEventClient inventory;
  private final CatalogEventDeliveryProperties properties;

  public CatalogEventOutboxDispatcher(CatalogEventOutboxMapper outbox,
                                      InventoryCatalogEventClient inventory,
                                      CatalogEventDeliveryProperties properties) {
    this.outbox = outbox;
    this.inventory = inventory;
    this.properties = properties;
  }

  @Scheduled(initialDelayString = "${commerceflow.catalog-event-delivery.initial-delay:2s}",
    fixedDelayString = "${commerceflow.catalog-event-delivery.fixed-delay:1s}")
  public void dispatch() {
    if (!properties.isEnabled()) {
      return;
    }
    for (CatalogEventOutbox event : outbox.pending(properties.getBatchSize())) {
      deliver(event);
    }
  }

  private void deliver(CatalogEventOutbox event) {
    try {
      inventory.deliver(event.getPayload());
      outbox.published(event.getId());
    } catch (RuntimeException exception) {
      String message = exception.getMessage() == null
        ? exception.getClass().getSimpleName() : exception.getMessage();
      outbox.failed(event.getId(), properties.getMaxAttempts(),
        Instant.now().plus(properties.getRetryDelay()), truncate(message));
    }
  }

  private String truncate(String message) {
    return message.length() <= 500 ? message : message.substring(0, 500);
  }
}
