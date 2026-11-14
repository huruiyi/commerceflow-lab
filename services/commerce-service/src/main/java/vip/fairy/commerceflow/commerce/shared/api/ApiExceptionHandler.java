package vip.fairy.commerceflow.commerce.shared.api;

import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import vip.fairy.commerceflow.commerce.identity.application.LoginRateLimitException;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<Map<String, Object>> authentication(AuthenticationException exception) {
    return response(HttpStatus.UNAUTHORIZED, "AUTH_INVALID", "Invalid email or password");
  }

  @ExceptionHandler(LoginRateLimitException.class)
  ResponseEntity<Map<String, Object>> rateLimited(LoginRateLimitException exception) {
    return response(HttpStatus.TOO_MANY_REQUESTS, "AUTH_RATE_LIMITED", exception.getMessage());
  }

  @ExceptionHandler(BusinessRuleException.class)
  ResponseEntity<Map<String, Object>> businessRule(BusinessRuleException exception) {
    return response(HttpStatus.CONFLICT, "BUSINESS_RULE", exception.getMessage());
  }

  @ExceptionHandler(TransientDataAccessException.class)
  ResponseEntity<Map<String, Object>> transientDatabaseConflict(TransientDataAccessException exception) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
      .header("Retry-After", "1")
      .body(body(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_CONCURRENCY_RETRY",
        "A concurrent database operation did not complete in time; retry the request"));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
    String message = exception.getBindingResult().getFieldErrors().stream()
      .findFirst()
      .map(error -> error.getField() + " " + error.getDefaultMessage())
      .orElse("Request validation failed");
    return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException exception) {
    return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException exception) {
    return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, Object>> illegalState(IllegalStateException exception) {
    return response(HttpStatus.CONFLICT, "BUSINESS_RULE", exception.getMessage());
  }

  private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(body(status, code, message));
  }

  private Map<String, Object> body(HttpStatus status, String code, String message) {
    return Map.of(
      "timestamp", Instant.now().toString(),
      "status", status.value(),
      "code", code,
      "message", message);
  }
}
