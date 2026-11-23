package vip.fairy.commerceflow.commerce.ordering.application.contract.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SettlementOrderV1 {

  List<Order> completed(Long merchant, Instant enabledAt, Instant cutoff);

  List<Line> lines(Long orderId);

  Optional<Order> find(Long merchant, Long orderId);

  record Order(Long id, Instant completedAt, BigDecimal total, BigDecimal freight, String currency) {
  }

  record Line(Long id, Long merchant, BigDecimal merchandise) {
  }
}
