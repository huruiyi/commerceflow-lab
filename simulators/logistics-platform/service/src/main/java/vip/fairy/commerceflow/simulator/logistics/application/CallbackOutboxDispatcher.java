package vip.fairy.commerceflow.simulator.logistics.application;

import vip.fairy.commerceflow.simulator.logistics.infrastructure.client.LogisticsCallbackClient;
import vip.fairy.commerceflow.simulator.logistics.infrastructure.configuration.CallbackDeliveryProperties;

import java.time.Instant;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CallbackOutboxDispatcher {

  private static final Logger log = LoggerFactory.getLogger(CallbackOutboxDispatcher.class);
  private final CallbackOutboxService outbox;
  private final LogisticsCallbackClient callbacks;
  private final CallbackDeliveryProperties properties;
  private final Counter published;
  private final Counter failed;
  private final Counter dead;

  public CallbackOutboxDispatcher(CallbackOutboxService outbox, LogisticsCallbackClient callbacks,
                                  CallbackDeliveryProperties properties, MeterRegistry registry) {
    this.outbox = outbox;
    this.callbacks = callbacks;
    this.properties = properties;
    this.published = registry.counter("commerceflow.logistics.callback.delivery", "result", "published");
    this.failed = registry.counter("commerceflow.logistics.callback.delivery", "result", "failed");
    this.dead = registry.counter("commerceflow.logistics.callback.delivery", "result", "dead");
  }

  @Scheduled(initialDelayString = "${logistics-platform.callback-delivery.initial-delay:2s}",
    fixedDelayString = "${logistics-platform.callback-delivery.scan-interval:1s}")
  public void dispatch() {
    if (!properties.isEnabled()) return;
    Instant now = Instant.now();
    for (Long id : outbox.dueIds(now, properties.getBatchSize())) {
      CallbackOutboxService.Delivery delivery = outbox.claim(id, Instant.now());
      if (delivery == null) continue;
      try {
        callbacks.send(delivery.callbackUrl(), delivery.payload());
        outbox.published(delivery);
        published.increment();
      } catch (Exception exception) {
        String status = outbox.failed(delivery, exception);
        failed.increment();
        if ("DEAD".equals(status)) dead.increment();
        log.warn("Logistics callback delivery failed for outbox {} attempt {}/{}",
          id, delivery.attempts(), delivery.maxAttempts());
      }
    }
  }
}
