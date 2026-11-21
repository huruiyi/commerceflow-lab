package vip.fairy.commerceflow.commerce.ordering.application.contract.v1;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface InvoiceOrderReadV1 {

  Optional<Order> find(long customerId, long orderId);

  record Order(BigDecimal total, BigDecimal gross, BigDecimal discount, BigDecimal freight, String currency,
               List<Line> lines) {
  }

  record Line(Long merchantId, BigDecimal gross, BigDecimal discount) {
  }
}
