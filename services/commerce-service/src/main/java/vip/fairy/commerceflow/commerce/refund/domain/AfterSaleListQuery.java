package vip.fairy.commerceflow.commerce.refund.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;

public record AfterSaleListQuery(Long orderId, UUID caseId, String status, Instant createdFrom, Instant createdBefore,
                                 Integer page, Integer size) {

  public AfterSaleListQuery {
    page = page == null ? 0 : page;
    size = size == null ? 20 : size;
  }

  public void validate(Set<String> allowedStatuses) {
    if (orderId != null && orderId < 1 || page < 0 || size < 1 || size > 50
        || status != null && !allowedStatuses.contains(status)
        || createdFrom != null && createdBefore != null && !createdFrom.isBefore(createdBefore)) {
      throw new IllegalArgumentException("Invalid after-sale search filters or page");
    }
  }

  public <T> Page<T> slice(long total, LongFunction<List<T>> load) {
    int index = total == 0 ? 0 : (int) Math.min(page, (total - 1) / size);
    var rows = total == 0 ? List.<T>of() : load.apply((long) index * size);
    return new Page<>(rows, total, index, size);
  }

  public String caseNumber() {
    return caseId == null ? null : caseId.toString();
  }

  public record Page<T>(List<T> rows, long total, int page, int size) {
  }
}
