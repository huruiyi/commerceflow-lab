package vip.fairy.commerceflow.inventory.infrastructure.transport;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryOutboundMapper;

@Component
public class InventoryOutboundEventPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(InventoryOutboundEventPublisher.class);

  private final InventoryOutboundMapper store;
  private final KafkaTemplate<String, String> kafka;

  public InventoryOutboundEventPublisher(InventoryOutboundMapper store, KafkaTemplate<String, String> kafka) {
    this.store = store;
    this.kafka = kafka;
  }

  @Scheduled(fixedDelayString = "${commerceflow.inventory.outbound-publish-delay:1s}")
  public void publish() {
    for (var row : store.pending(20)) {
      try {
        kafka.send("inventory-outbound-events", row.eventId(), row.payload()).get(7, TimeUnit.SECONDS);
        store.published(row.eventId());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      } catch (ExecutionException | TimeoutException | RuntimeException failure) {
        store.retry(row.eventId());
        LOG.warn("Could not publish inventory outbound event {}", row.eventId(), failure);
        return;
      }
    }
  }
}
