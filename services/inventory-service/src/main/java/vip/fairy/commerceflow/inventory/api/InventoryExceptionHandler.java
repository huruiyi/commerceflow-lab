package vip.fairy.commerceflow.inventory.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class InventoryExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalidRequest(IllegalArgumentException exception) {
    return problem(HttpStatus.BAD_REQUEST, "INVENTORY_REQUEST_INVALID", exception.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail businessConflict(IllegalStateException exception) {
    return problem(HttpStatus.CONFLICT, "INVENTORY_STATE_CONFLICT", exception.getMessage());
  }

  private ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code);
    return problem;
  }
}
