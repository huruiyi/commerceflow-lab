package lab.commerceflow.logistics;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CallbackOutboxService {
  private final JdbcTemplate jdbc;
  private final CallbackDeliveryProperties properties;
  private final Counter manualReplays;

  public CallbackOutboxService(JdbcTemplate jdbc, CallbackDeliveryProperties properties,
      MeterRegistry registry) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.manualReplays = registry.counter("commerceflow.logistics.callback.replays");
  }

  @Transactional
  public void enqueue(Long shipmentId, String eventId, String callbackUrl, String payload) {
    jdbc.update("""
        INSERT INTO callback_outbox(
          shipment_id,event_id,callback_url,payload,max_attempts)
        VALUES(?,?,?,?,?)
        """, shipmentId, eventId, callbackUrl, payload, properties.getMaxAttempts());
    Long id = jdbc.queryForObject("SELECT id FROM callback_outbox WHERE event_id=?", Long.class,
        eventId);
    audit(id, "OUTBOX_CREATED", null, "NEW", "SYSTEM", "Shipment event committed");
  }

  public List<CallbackOutboxView> list(String status, int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 500));
    if (status != null && !status.isBlank()) {
      String normalized = status.trim().toUpperCase();
      if (!Set.of("NEW", "PROCESSING", "RETRY", "PUBLISHED", "DEAD").contains(normalized)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CALLBACK_OUTBOX_STATUS_INVALID");
      }
      return jdbc.query("SELECT * FROM callback_outbox WHERE status=? ORDER BY id DESC LIMIT ?",
          (rs, row) -> map(rs), normalized, safeLimit);
    }
    return jdbc.query("SELECT * FROM callback_outbox ORDER BY id DESC LIMIT ?",
        (rs, row) -> map(rs), safeLimit);
  }

  public List<CallbackOutboxView.AuditEvent> events(Long id) {
    require(id);
    return jdbc.query("""
        SELECT id,event_type,from_status,to_status,operated_by,reason,created_at
        FROM callback_outbox_events WHERE outbox_id=? ORDER BY created_at,id
        """, (rs, row) -> new CallbackOutboxView.AuditEvent(rs.getLong("id"),
        rs.getString("event_type"), rs.getString("from_status"), rs.getString("to_status"),
        rs.getString("operated_by"), rs.getString("reason"),
        rs.getTimestamp("created_at").toInstant()), id);
  }

  @Transactional
  public CallbackOutboxView replay(Long id, String operatedBy, String reason) {
    if (operatedBy == null || operatedBy.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OPERATED_BY_REQUIRED");
    }
    if (reason == null || reason.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "REPLAY_REASON_REQUIRED");
    }
    requireForUpdate(id);
    int updated = jdbc.update("""
        UPDATE callback_outbox
        SET status='RETRY',attempts=0,max_attempts=?,next_attempt_at=CURRENT_TIMESTAMP(6),
            lease_until=NULL,claimed_by=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=? AND status='DEAD'
        """, properties.getMaxAttempts(), id);
    if (updated != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ONLY_DEAD_CALLBACK_CAN_BE_REPLAYED");
    }
    audit(id, "MANUAL_REPLAY_REQUESTED", "DEAD", "RETRY", operatedBy.trim(), reason.trim());
    manualReplays.increment();
    return require(id);
  }

  public List<Long> dueIds(Instant now, int limit) {
    return jdbc.queryForList("""
        SELECT id FROM callback_outbox
        WHERE attempts<max_attempts AND (
          (status IN ('NEW','RETRY') AND next_attempt_at<=?) OR
          (status='PROCESSING' AND lease_until<?)
        )
        ORDER BY next_attempt_at,id LIMIT ?
        """, Long.class, Timestamp.from(now), Timestamp.from(now), limit);
  }

  @Transactional
  public Delivery claim(Long id, Instant now) {
    String owner = UUID.randomUUID().toString();
    Instant leaseUntil = now.plus(properties.getClaimLease());
    int claimed = jdbc.update("""
        UPDATE callback_outbox
        SET status='PROCESSING',attempts=attempts+1,lease_until=?,claimed_by=?,updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=? AND attempts<max_attempts AND (
          (status IN ('NEW','RETRY') AND next_attempt_at<=?) OR
          (status='PROCESSING' AND lease_until<?)
        )
        """, Timestamp.from(leaseUntil), owner, id, Timestamp.from(now), Timestamp.from(now));
    if (claimed != 1) return null;
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM callback_outbox WHERE id=?", id);
    audit(id, "DELIVERY_CLAIMED", null, "PROCESSING", owner,
        "Attempt " + row.get("attempts") + " of " + row.get("max_attempts"));
    Object payload = row.get("payload");
    String body = payload instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
        : String.valueOf(payload);
    return new Delivery(id, (String) row.get("callback_url"), body,
        ((Number) row.get("attempts")).intValue(), ((Number) row.get("max_attempts")).intValue(), owner);
  }

  @Transactional
  public void published(Delivery delivery) {
    int updated = jdbc.update("""
        UPDATE callback_outbox
        SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(6),lease_until=NULL,claimed_by=NULL,
            last_error=NULL,updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=? AND status='PROCESSING' AND claimed_by=?
        """, delivery.id(), delivery.owner());
    if (updated == 1) audit(delivery.id(), "DELIVERY_PUBLISHED", "PROCESSING", "PUBLISHED",
        delivery.owner(), "Callback accepted by storefront");
  }

  @Transactional
  public String failed(Delivery delivery, Exception exception) {
    String status = delivery.attempts() >= delivery.maxAttempts() ? "DEAD" : "RETRY";
    Instant nextAttempt = Instant.now().plus(properties.getRetryDelay());
    String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    if (message.length() > 1000) message = message.substring(0, 1000);
    int updated = jdbc.update("""
        UPDATE callback_outbox
        SET status=?,next_attempt_at=?,lease_until=NULL,claimed_by=NULL,last_error=?,
            updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=? AND status='PROCESSING' AND claimed_by=?
        """, status, Timestamp.from(nextAttempt), message, delivery.id(), delivery.owner());
    if (updated == 1) audit(delivery.id(), "DELIVERY_FAILED", "PROCESSING", status,
        delivery.owner(), message);
    return status;
  }

  private CallbackOutboxView require(Long id) {
    List<CallbackOutboxView> rows = jdbc.query("SELECT * FROM callback_outbox WHERE id=?",
        (rs, row) -> map(rs), id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
        "CALLBACK_OUTBOX_NOT_FOUND");
    return rows.getFirst();
  }

  private void requireForUpdate(Long id) {
    if (jdbc.queryForList("SELECT id FROM callback_outbox WHERE id=? FOR UPDATE", Long.class, id)
        .isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CALLBACK_OUTBOX_NOT_FOUND");
    }
  }

  private CallbackOutboxView map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new CallbackOutboxView(rs.getLong("id"), rs.getLong("shipment_id"),
        rs.getString("event_id"), rs.getString("callback_url"), rs.getString("status"),
        rs.getInt("attempts"), rs.getInt("max_attempts"),
        instant(rs, "next_attempt_at"), instant(rs, "lease_until"), rs.getString("claimed_by"),
        rs.getString("last_error"), instant(rs, "created_at"), instant(rs, "published_at"),
        instant(rs, "updated_at"));
  }

  private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private void audit(Long id, String eventType, String fromStatus, String toStatus,
      String operatedBy, String reason) {
    jdbc.update("""
        INSERT INTO callback_outbox_events(
          outbox_id,event_type,from_status,to_status,operated_by,reason)
        VALUES(?,?,?,?,?,?)
        """, id, eventType, fromStatus, toStatus, operatedBy,
        reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
  }

  public record Delivery(Long id, String callbackUrl, String payload, int attempts,
                         int maxAttempts, String owner) {}
}
