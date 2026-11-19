package vip.fairy.commerceflow.commerce.ordering.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemSnapshot;

final class CouponAllocation {

  private CouponAllocation() {
  }

  static List<OrderItemSnapshot> allocate(List<OrderItemSnapshot> items, BigDecimal subtotal, BigDecimal discount) {
    BigInteger total = cents(subtotal);
    BigInteger reduction = cents(discount);
    if (reduction.signum() <= 0 || reduction.compareTo(total) >= 0) {
      throw new IllegalArgumentException("Coupon reduction must be positive and less than merchandise");
    }
    List<Share> shares = new ArrayList<>(items.size());
    long remaining = reduction.longValueExact();
    for (int i = 0; i < items.size(); i++) {
      var divided = cents(items.get(i).subtotal()).multiply(reduction).divideAndRemainder(total);
      long base = divided[0].longValueExact();
      shares.add(new Share(i, items.get(i).productId(), base, divided[1]));
      remaining -= base;
    }
    shares.sort(Comparator.comparing(Share::remainder).reversed()
      .thenComparing(Share::productId).thenComparingInt(Share::index));
    List<OrderItemSnapshot> priced = new ArrayList<>(items);
    for (int i = 0; i < shares.size(); i++) {
      Share share = shares.get(i);
      var item = items.get(share.index());
      priced.set(share.index(), new OrderItemSnapshot(item.productId(), item.merchantId(), item.productName(),
        item.thumbnailUrl(), item.unitPrice(), item.quantity(),
        BigDecimal.valueOf(share.cents() + (i < remaining ? 1 : 0), 2)));
    }
    return List.copyOf(priced);
  }

  private static BigInteger cents(BigDecimal money) {
    return money.movePointRight(2).toBigIntegerExact();
  }

  private record Share(int index, Long productId, long cents, BigInteger remainder) {
  }
}
