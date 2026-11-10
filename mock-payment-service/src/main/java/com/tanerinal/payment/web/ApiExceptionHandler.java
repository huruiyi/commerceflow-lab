package com.tanerinal.payment.web;

import com.tanerinal.payment.service.LoginRateLimitException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, String>> status(ResponseStatusException exception) {
    return ResponseEntity.status(exception.getStatusCode())
        .body(Map.of("message", exception.getReason() == null ? "请求失败" : exception.getReason()));
  }

  @ExceptionHandler(LoginRateLimitException.class)
  ResponseEntity<Map<String, String>> rateLimit(LoginRateLimitException exception) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", exception.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
    String message = exception.getBindingResult().getFieldErrors().stream()
        .findFirst().map(error -> error.getField() + " " + error.getDefaultMessage()).orElse("参数错误");
    return ResponseEntity.badRequest().body(Map.of("message", message));
  }
}
