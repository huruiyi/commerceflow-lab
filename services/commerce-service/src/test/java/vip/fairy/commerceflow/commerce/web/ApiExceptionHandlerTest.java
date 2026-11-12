package vip.fairy.commerceflow.commerce.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {
  @Test
  void lockWaitFailureHasAStableRetryableContract() {
    var response = new ApiExceptionHandler().transientDatabaseConflict(
      new CannotAcquireLockException("Lock wait timeout exceeded"));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertEquals("1", response.getHeaders().getFirst("Retry-After"));
    assertEquals("DATABASE_CONCURRENCY_RETRY", response.getBody().get("code"));
  }
}
