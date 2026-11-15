package vip.fairy.commerceflow.commerce.inventory.infrastructure;

import java.time.Instant;

public record DeliveryRecoveryView(String commandId, String commandType, Long orderId, String status,
                                   int attempts, String lastError, String recoveredBy,
                                   String recoveryReason, Instant recoveredAt, Instant createdAt) {
}
