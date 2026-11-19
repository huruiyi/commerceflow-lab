package vip.fairy.commerceflow.inventory.domain;

public record StockAvailability(long productId, boolean listed, int available) {
}
