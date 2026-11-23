package vip.fairy.commerceflow.commerce.notification.domain;

import java.time.Instant;

public final class DeliveryAudit {

  private DeliveryAudit() {
  }

  public record Delivery(String eventId, String topic, String status, int attempts, String lastError) {
  }

  public record Recovery(String requestId, long actorId, String reason, int priorAttempts, String priorError,
                         Instant createdAt) {
  }
}
