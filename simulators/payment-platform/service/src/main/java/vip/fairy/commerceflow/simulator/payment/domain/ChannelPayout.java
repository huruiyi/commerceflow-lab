package vip.fairy.commerceflow.simulator.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Identifies one reserved or executed merchant disbursement. */
public record ChannelPayout(String attemptId, String statementId, Long beneficiaryId, BigDecimal amount,
                            String currency, String channelPayoutNo, String status, boolean simulateFailure,
                            Instant createdAt, Instant resolvedAt, Instant notifiedAt) {
}
