package vip.fairy.commerceflow.simulator.logistics;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CallbackOutboxService {
  private final LogisticsDataMapper dataMapper;
  private final CallbackDeliveryProperties properties;
  private final Counter manualReplays;

  public CallbackOutboxService(LogisticsDataMapper dataMapper, CallbackDeliveryProperties properties,
                               MeterRegistry registry) {
    this.dataMapper = dataMapper;
    this.properties = properties;
    this.manualReplays = registry.counter("commerceflow.logistics.callback.replays");
  }

  @Transactional
  public void enqueue(Long shipmentId, String eventId, String callbackUrl, String payload) {
    dataMapper.insertOutbox(shipmentId, eventId, callbackUrl, payload, properties.getMaxAttempts());
    Long id = dataMapper.outboxIdByEventId(eventId);
    audit(id, "OUTBOX_CREATED", null, "NEW", "SYSTEM", "Shipment event committed");
  }

  public List<CallbackOutboxView> list(String status, int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 500));
    if (status != null && !status.isBlank()) {
      String normalized = status.trim().toUpperCase();
      if (!Set.of("NEW", "PROCESSING", "RETRY", "PUBLISHED", "DEAD").contains(normalized)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CALLBACK_OUTBOX_STATUS_INVALID");
      }
      return dataMapper.listOutbox(normalized, safeLimit).stream().map(this::map).toList();
    }
    return dataMapper.listOutbox(null, safeLimit).stream().map(this::map).toList();
  }

  public List<CallbackOutboxView.AuditEvent> events(Long id) {
    require(id);
    return dataMapper.outboxEvents(id).stream()
      .map(row -> new CallbackOutboxView.AuditEvent(number(row, "id"),
        string(row, "event_type"), string(row, "from_status"), string(row, "to_status"),
        string(row, "operated_by"), string(row, "reason"), instant(row, "created_at")))
      .toList();
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
    int updated = dataMapper.replayOutbox(id, properties.getMaxAttempts());
    if (updated != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ONLY_DEAD_CALLBACK_CAN_BE_REPLAYED");
    }
    audit(id, "MANUAL_REPLAY_REQUESTED", "DEAD", "RETRY", operatedBy.trim(), reason.trim());
    manualReplays.increment();
    return require(id);
  }

  public List<Long> dueIds(Instant now, int limit) {
    return dataMapper.dueOutboxIds(now, limit);
  }

  @Transactional
  public Delivery claim(Long id, Instant now) {
    String owner = UUID.randomUUID().toString();
    Instant leaseUntil = now.plus(properties.getClaimLease());
    int claimed = dataMapper.claimOutbox(id, now, leaseUntil, owner);
    if (claimed != 1) return null;
    Map<String, Object> row = dataMapper.outboxById(id);
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
    int updated = dataMapper.publishOutbox(delivery.id(), delivery.owner());
    if (updated == 1) audit(delivery.id(), "DELIVERY_PUBLISHED", "PROCESSING", "PUBLISHED",
      delivery.owner(), "Callback accepted by storefront");
  }

  @Transactional
  public String failed(Delivery delivery, Exception exception) {
    String status = delivery.attempts() >= delivery.maxAttempts() ? "DEAD" : "RETRY";
    Instant nextAttempt = Instant.now().plus(properties.getRetryDelay());
    String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    if (message.length() > 1000) message = message.substring(0, 1000);
    int updated = dataMapper.failOutbox(delivery.id(), delivery.owner(), status, nextAttempt, message);
    if (updated == 1) audit(delivery.id(), "DELIVERY_FAILED", "PROCESSING", status,
      delivery.owner(), message);
    return status;
  }

  private CallbackOutboxView require(Long id) {
    Map<String, Object> row = dataMapper.outboxById(id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
      "CALLBACK_OUTBOX_NOT_FOUND");
    return map(row);
  }

  private void requireForUpdate(Long id) {
    if (dataMapper.lockOutbox(id) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CALLBACK_OUTBOX_NOT_FOUND");
    }
  }

  private CallbackOutboxView map(Map<String, Object> row) {
    return new CallbackOutboxView(number(row, "id"), number(row, "shipment_id"),
      string(row, "event_id"), string(row, "callback_url"), string(row, "status"),
      integer(row, "attempts"), integer(row, "max_attempts"),
      instant(row, "next_attempt_at"), instant(row, "lease_until"), string(row, "claimed_by"),
      string(row, "last_error"), instant(row, "created_at"), instant(row, "published_at"),
      instant(row, "updated_at"));
  }

  private Long number(Map<String, Object> row, String column) {
    Object value = row.get(column);
    return value == null ? null : ((Number) value).longValue();
  }

  private int integer(Map<String, Object> row, String column) {
    return ((Number) row.get(column)).intValue();
  }

  private String string(Map<String, Object> row, String column) {
    Object value = row.get(column);
    return value == null ? null : String.valueOf(value);
  }

  private Instant instant(Map<String, Object> row, String column) {
    Object value = row.get(column);
    if (value == null) return null;
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    return (Instant) value;
  }

  private void audit(Long id, String eventType, String fromStatus, String toStatus,
                     String operatedBy, String reason) {
    dataMapper.insertOutboxEvent(id, eventType, fromStatus, toStatus, operatedBy,
      reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
  }

  public record Delivery(Long id, String callbackUrl, String payload, int attempts,
                         int maxAttempts, String owner) {
  }
}
