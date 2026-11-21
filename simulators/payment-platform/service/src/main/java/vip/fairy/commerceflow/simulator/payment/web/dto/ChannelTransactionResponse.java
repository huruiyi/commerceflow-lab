package vip.fairy.commerceflow.simulator.payment.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ChannelTransactionResponse(String channelTransactionNo, String merchantRequestNo,
                                         String channel, String subject, BigDecimal amount, String currency,
                                         String status,
                                         String faultScenario, String actionType, String checkoutUrl, String qrPayload,
                                         LocalDateTime expiresAt, LocalDateTime paidAt, LocalDateTime closedAt,
                                         LocalDateTime createdAt,
                                         @com.fasterxml.jackson.annotation.JsonInclude(
                                           com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                         Boolean canClose) {
}
