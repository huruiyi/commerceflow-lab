package vip.fairy.commerceflow.inventory.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vip.fairy.commerceflow.inventory.application.service.InventoryBusinessRuleException;
import vip.fairy.commerceflow.inventory.application.service.InventoryNotFoundException;

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

  @ExceptionHandler(InventoryBusinessRuleException.class)
  ProblemDetail businessRule(InventoryBusinessRuleException exception) {
    return problem(HttpStatus.CONFLICT, "INVENTORY_BUSINESS_RULE", exception.getMessage());
  }

  @ExceptionHandler(InventoryNotFoundException.class)
  ProblemDetail notFound(InventoryNotFoundException exception) {
    return problem(HttpStatus.NOT_FOUND, "INVENTORY_NOT_FOUND", exception.getMessage());
  }

  private ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code);
    return problem;
  }
}
