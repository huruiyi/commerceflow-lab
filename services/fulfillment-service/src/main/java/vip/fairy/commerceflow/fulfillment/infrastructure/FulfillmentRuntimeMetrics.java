package vip.fairy.commerceflow.fulfillment.infrastructure;

import java.time.Instant;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentRuntimeMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(FulfillmentRuntimeMetrics.class);
  private static final String PREFIX = "commerceflow.fulfillment.";

  private final ShadowDatabases databases;
  private volatile Snapshot snapshot = new Snapshot(0, 0, 0, 0, 0);
  private volatile long lastRefresh;
  private volatile boolean refreshSucceeded;

  public FulfillmentRuntimeMetrics(ShadowDatabases databases, FulfillmentMode mode, MeterRegistry registry) {
    this.databases = databases;
    Gauge.builder(PREFIX + "execution.enabled", mode, value -> value.execution() ? 1 : 0).register(registry);
    register(registry, "jobs.pending", value -> value.snapshot.pending());
    register(registry, "jobs.review.required", value -> value.snapshot.reviewRequired());
    register(registry, "jobs.leases.expired", value -> value.snapshot.expiredLeases());
    register(registry, "jobs.oldest.due.seconds", value -> value.snapshot.oldestDueSeconds());
    register(registry, "quarantined", value -> value.snapshot.quarantined());
    register(registry, "metrics.refresh.success", value -> value.refreshSucceeded ? 1 : 0);
    register(registry, "metrics.last.success.seconds", value -> value.lastRefresh);
  }

  @Scheduled(fixedDelayString = "${commerceflow.fulfillment.metrics.refresh-delay:10s}")
  public void refresh() {
    try (var session = databases.target()) {
      snapshot = session.getMapper(ExecutionMapper.class).runtimeMetrics();
      lastRefresh = Instant.now().getEpochSecond();
      refreshSucceeded = true;
    } catch (RuntimeException failure) {
      refreshSucceeded = false;
      LOGGER.warn("Could not refresh fulfillment runtime metrics: {}", failure.getMessage());
    }
  }

  private void register(MeterRegistry registry, String metric, ToDoubleFunction<FulfillmentRuntimeMetrics> value) {
    Gauge.builder(PREFIX + metric, this, value).register(registry);
  }

  public record Snapshot(long pending, long reviewRequired, long expiredLeases, long oldestDueSeconds,
                         long quarantined) {
  }
}
