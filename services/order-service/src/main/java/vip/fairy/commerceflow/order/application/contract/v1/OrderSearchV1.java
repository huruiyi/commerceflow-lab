package vip.fairy.commerceflow.order.application.contract.v1;

import java.time.Instant;
import java.util.List;

public final class OrderSearchV1 {

  private OrderSearchV1() {
  }

  public record Request(Long customerId, Long merchantId, Criteria criteria) {
  }

  public record Criteria(String keyword, String status, Instant from, Instant until, int page, int size,
                         List<Long> productIds) {
  }

  public record Result(List<OrderStoreV1.OrderView> orders, long total, int page, int size,
                       long pendingConfirm, long pendingShip) {
  }

  public record PendingCounts(long pendingConfirm, long pendingShip) {
  }
}
