package lab.commerceflow.logistics;

import java.time.Instant;

public record CallbackOutboxView(Long id, Long shipmentId, String eventId, String callbackUrl,
                                 String status, int attempts, int maxAttempts,
                                 Instant nextAttemptAt, Instant leaseUntil, String claimedBy,
                                 String lastError, Instant createdAt, Instant publishedAt,
                                 Instant updatedAt) {
  public record AuditEvent(Long id, String eventType, String fromStatus, String toStatus,
                           String operatedBy, String reason, Instant createdAt) {}
}
