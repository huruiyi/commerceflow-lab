package vip.fairy.commerceflow.commerce.merchant.domain;

import java.time.Instant;

/** A store shares its immutable identifier with the owning merchant account. */
public record MerchantStore(Long merchantId, String name, String description, String status,
                            Long reviewedBy, Instant updatedAt) {
}
