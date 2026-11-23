package vip.fairy.commerceflow.warehouse;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record WarehouseJobSearch(Long orderId, String jobId, String status, Instant createdFrom, Instant createdBefore,
                          Integer page, Integer size) {

  WarehouseJobSearch {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
    if (page < 1 || size < 1 || size > 200 || orderId != null && orderId < 1
        || createdFrom != null && createdBefore != null && !createdFrom.isBefore(createdBefore)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid warehouse search filters or page");
    }
    jobId = blankToNull(jobId);
    if (jobId != null && !isJobId(jobId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid warehouse job number");
    }
    status = blankToNull(status);
  }

  void validateStatus(Set<String> allowed) {
    if (status != null && !allowed.contains(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid warehouse job status");
    }
  }

  <T> Page<T> slice(long total, LongFunction<List<T>> load) {
    int current = total == 0 ? 1 : (int) Math.min(page, (total - 1) / size + 1);
    var rows = total == 0 ? List.<T>of() : load.apply((long) (current - 1) * size);
    return new Page<>(rows, total, current, size);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static boolean isJobId(String id) {
    try {
      return UUID.fromString(id).toString().equals(id);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  record Page<T>(List<T> rows, long total, int page, int size) {
  }
}
