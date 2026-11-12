package vip.fairy.commerceflow.simulator.logistics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CallbackOutboxMetrics {
  public CallbackOutboxMetrics(LogisticsDataMapper dataMapper, MeterRegistry registry) {
    register(dataMapper, registry, "new", "NEW");
    register(dataMapper, registry, "processing", "PROCESSING");
    register(dataMapper, registry, "retry", "RETRY");
    register(dataMapper, registry, "dead", "DEAD");
  }

  private void register(LogisticsDataMapper dataMapper, MeterRegistry registry, String metricStatus,
                        String databaseStatus) {
    Gauge.builder("commerceflow.logistics.callback.outbox", dataMapper,
        source -> source.countOutboxByStatus(databaseStatus))
      .tag("status", metricStatus)
      .description("Current logistics callback outbox rows by status")
      .register(registry);
  }
}
