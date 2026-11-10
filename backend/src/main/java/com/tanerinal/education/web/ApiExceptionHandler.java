package com.tanerinal.education.web;

import com.tanerinal.education.service.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
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
    return response(HttpStatus.UNAUTHORIZED, "Invalid email or password");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
    String message = exception.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(error -> error.getField() + " " + error.getDefaultMessage())
        .orElse("Request validation failed");
    return response(HttpStatus.BAD_REQUEST, message);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException exception) {
    return response(HttpStatus.NOT_FOUND, exception.getMessage());
  }

  private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of(
        "timestamp", Instant.now().toString(),
        "status", status.value(),
        "message", message));
  }
}
