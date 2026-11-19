package vip.fairy.commerceflow.commerce.ordering.application.contract.v1;

import java.util.List;

public record OrderListPage<T>(List<T> orders, long total, int page, int size, long pendingConfirm, long pendingShip) {
  public <V> OrderListPage<V> map(java.util.function.Function<T, V> mapper) {
    return new OrderListPage<>(orders.stream().map(mapper).toList(), total, page, size, pendingConfirm, pendingShip);
  }
}
