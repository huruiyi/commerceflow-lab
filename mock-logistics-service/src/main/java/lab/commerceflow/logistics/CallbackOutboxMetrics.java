package lab.commerceflow.logistics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CallbackOutboxMetrics {
  public CallbackOutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
    register(jdbc, registry, "new", "NEW");
    register(jdbc, registry, "processing", "PROCESSING");
    register(jdbc, registry, "retry", "RETRY");
    register(jdbc, registry, "dead", "DEAD");
  }

  private void register(JdbcTemplate jdbc, MeterRegistry registry, String metricStatus,
      String databaseStatus) {
    Gauge.builder("commerceflow.logistics.callback.outbox", jdbc,
            source -> source.queryForObject(
                "SELECT COUNT(*) FROM callback_outbox WHERE status=?", Long.class,
                databaseStatus))
        .tag("status", metricStatus)
        .description("Current logistics callback outbox rows by status")
        .register(registry);
  }
}
