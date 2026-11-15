package vip.fairy.commerceflow.catalog.application;

public class CatalogVersionConflictException extends RuntimeException {

  public CatalogVersionConflictException(String message) {
    super(message);
  }
}
