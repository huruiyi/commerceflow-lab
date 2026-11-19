package vip.fairy.commerceflow.commerce.ordering.application;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CouponAllocationTest {

  @Test
  void assignsTiedRemainderByProductAndPreservesOriginalOrder() {
    var items = List.of(line(9L), line(1L), line(4L));
    var allocated = CouponAllocation.allocate(items, new BigDecimal("3.00"), new BigDecimal("0.02"));
    assertEquals(List.of(new BigDecimal("0.00"), new BigDecimal("0.01"), new BigDecimal("0.01")),
      allocated.stream().map(OrderItemSnapshot::discountAmount).toList());
  }

  private OrderItemSnapshot line(long id) {
    return new OrderItemSnapshot(id, 1L, "Item", "", new BigDecimal("1.00"), 1, BigDecimal.ZERO);
  }
}
