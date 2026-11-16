package vip.fairy.commerceflow.inventory.domain;

/** 商品当前可售、预占和已售余额。 */
public record ProductInventory(
  Long id,
  int stockAvailable,
  int stockReserved,
  int stockSold
) {
}
