package vip.fairy.commerceflow.commerce.shared.application;

import java.math.BigDecimal;

/**
 * 模块化单体内同步更新 inventory 商品快照的过渡契约；服务化后由 catalog V1 事件替代。
 */
public record CatalogSnapshotChanged(Long productId, Long merchantId, String productName,
                                     String thumbnailUrl, BigDecimal unitPrice) {
}
