package vip.fairy.commerceflow.simulator.payment.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ChannelRefundResponse(String channelRefundNo, String merchantRefundNo, String channelTransactionNo,
                                    String channel,
                                    BigDecimal amount, String currency, String reasonCode, String status,
                                    String faultScenario, String failureReason,
                                    LocalDateTime succeededAt, LocalDateTime failedAt, LocalDateTime createdAt) {
}
