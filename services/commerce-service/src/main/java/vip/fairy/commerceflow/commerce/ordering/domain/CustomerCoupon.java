package vip.fairy.commerceflow.commerce.ordering.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerCoupon(String id, String campaignId, Long customerId, Long merchantId, String title,
                             BigDecimal minimumAmount, BigDecimal discountAmount, Instant startsAt,
                             Instant expiresAt, boolean enabled, String status) {
}
