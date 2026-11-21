package vip.fairy.commerceflow.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class MessagingDeliveryMetrics {
  MessagingDeliveryMetrics(
    MessagingMapper mapper,
    MeterRegistry registry,
    @Value("${messaging.monitoring.failure-window}") Duration failureWindow
  ) {
    long windowSeconds = failureWindow.toSeconds();
    if (windowSeconds <= 0) {
      throw new IllegalArgumentException("Messaging failure window must be at least one second");
    }
    for (String channel : List.of("SMS", "EMAIL")) {
      Gauge.builder("commerceflow.messaging.delivery.failures.recent", mapper,
          source -> source.countRecentFailures(channel, windowSeconds))
        .description("Failed deliveries in the configured rolling time window")
        .tag("channel", channel)
        .register(registry);
    }
  }
}
