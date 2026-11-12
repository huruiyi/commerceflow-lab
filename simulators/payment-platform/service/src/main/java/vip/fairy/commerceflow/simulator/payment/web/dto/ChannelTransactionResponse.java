package vip.fairy.commerceflow.simulator.payment.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ChannelTransactionResponse(String channelTransactionNo, String merchantRequestNo,
                                         String channel, String subject, BigDecimal amount, String currency,
                                         String status,
                                         String faultScenario, String actionType, String checkoutUrl, String qrPayload,
                                         LocalDateTime expiresAt, LocalDateTime paidAt, LocalDateTime closedAt,
                                         LocalDateTime createdAt) {
}
