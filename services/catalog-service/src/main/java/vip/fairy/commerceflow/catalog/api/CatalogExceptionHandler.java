package vip.fairy.commerceflow.catalog.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vip.fairy.commerceflow.catalog.application.CatalogVersionConflictException;
import vip.fairy.commerceflow.catalog.application.ResourceNotFoundException;

import java.util.Map;

@RestControllerAdvice
public class CatalogExceptionHandler {

  @ExceptionHandler(ResourceNotFoundException.class)
  ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
      .body(Map.of("code", "RESOURCE_NOT_FOUND", "message", exception.getMessage()));
  }

  @ExceptionHandler(CatalogVersionConflictException.class)
  ResponseEntity<Map<String, String>> conflict(CatalogVersionConflictException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
      .body(Map.of("code", "CATALOG_VERSION_CONFLICT", "message", exception.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
    return ResponseEntity.badRequest()
      .body(Map.of("code", "INVALID_REQUEST", "message", exception.getMessage()));
  }
}
