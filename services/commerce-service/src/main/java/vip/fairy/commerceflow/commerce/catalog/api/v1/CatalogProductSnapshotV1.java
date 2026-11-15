package vip.fairy.commerceflow.commerce.catalog.api.v1;

import java.math.BigDecimal;

public record CatalogProductSnapshotV1(int schemaVersion, Long productId, Long merchantId,
                                       String name, BigDecimal unitPrice, String thumbnailUrl,
                                       String lifecycleStatus, long businessVersion) {

}
