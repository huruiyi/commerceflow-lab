package vip.fairy.commerceflow.payment.infrastructure.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRuntimeMetricsStore {

  private final JdbcTemplate jdbc;

  public PaymentRuntimeMetricsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Snapshot snapshot() {
    return new Snapshot(
      count("SELECT COUNT(*) FROM payment_outbox "
        + "WHERE status IN ('NEW','RETRY','PROCESSING')"),
      count("SELECT COUNT(*) FROM payment_command_outbox "
        + "WHERE status IN ('NEW','RETRY','IN_FLIGHT')"),
      count("SELECT COUNT(*) FROM payment_process_managers WHERE status<>'COMPLETED'"),
      count("SELECT COUNT(*) FROM payment_process_managers "
        + "WHERE status IN ('RECONCILIATION_REQUIRED','COMPENSATION_REQUIRED')"),
      count("SELECT COUNT(*) FROM payment_shadow_orphan_quarantine WHERE status='OPEN'"),
      count("SELECT COUNT(*) FROM refund_records WHERE status IN ('REQUESTED','PROCESSING')")
    );
  }

  private long count(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? 0 : value;
  }

  public record Snapshot(long outboxPending, long commandOutboxPending, long processPending,
                         long processRecoveryRequired, long orphanOpen, long refundProcessing) {
  }
}
