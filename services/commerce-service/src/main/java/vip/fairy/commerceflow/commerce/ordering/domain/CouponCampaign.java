package vip.fairy.commerceflow.commerce.ordering.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record CouponCampaign(String id, Long merchantId, String title, BigDecimal minimumAmount,
                             BigDecimal discountAmount, int supply, int issued, Instant startsAt,
                             Instant expiresAt, boolean enabled) {
}
