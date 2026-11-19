package vip.fairy.commerceflow.commerce.ordering.application.contract.v1;

import java.time.Instant;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

public record OrderListQuery(String keyword, String status, Instant from, Instant until, int page, int size,
                             List<Long> productIds) {
  private static final Set<String> STATUSES = Set.of("PENDING_PAYMENT", "PAID", "CONFIRMED", "SHIPPED",
    "RECEIVED", "COMPLETED", "CANCELLING", "CANCELLED", "REFUNDING", "REFUNDED");

  public static OrderListQuery from(String keyword, String status, LocalDate from, LocalDate to,
                                    int page, int size, String zone) {
    String query = keyword == null ? "" : keyword.strip();
    String state = status == null ? "" : status.strip();
    if (query.length() > 120 || page < 0 || size < 1 || size > 20 || (!state.isEmpty() && !STATUSES.contains(state))
        || (from != null && to != null && to.isBefore(from))) {
      throw new IllegalArgumentException("Invalid order list filters");
    }
    try {
      ZoneId timeZone = ZoneId.of(zone);
      return new OrderListQuery(query, state, startOfDay(from, timeZone),
        to == null ? null : startOfDay(to.plusDays(1), timeZone), page, size, List.of());
    } catch (DateTimeException exception) {
      throw new IllegalArgumentException("Invalid order date or time zone", exception);
    }
  }

  private static Instant startOfDay(LocalDate date, ZoneId zone) {
    return date == null ? null : date.atStartOfDay(zone).toInstant();
  }

  public OrderListQuery withProductIds(List<Long> ids) {
    return new OrderListQuery(keyword, status, from, until, page, size, ids);
  }
}
