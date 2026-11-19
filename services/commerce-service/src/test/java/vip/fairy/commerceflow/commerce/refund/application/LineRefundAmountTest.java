package vip.fairy.commerceflow.commerce.refund.application;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LineRefundAmountTest {

  @Test
  void allocatesRemainderWithoutExceedingOriginalNet() {
    BigDecimal net = new BigDecimal("290.00");
    assertEquals(new BigDecimal("96.66"), LineRefundAmount.calculate(net, 3, 0, 1));
    assertEquals(new BigDecimal("96.67"), LineRefundAmount.calculate(net, 3, 1, 1));
    assertEquals(new BigDecimal("96.67"), LineRefundAmount.calculate(net, 3, 2, 1));
    assertEquals(net, LineRefundAmount.calculate(net, 3, 0, 3));
    assertThrows(IllegalArgumentException.class, () -> LineRefundAmount.calculate(net, 3, 2, 2));
  }
}
