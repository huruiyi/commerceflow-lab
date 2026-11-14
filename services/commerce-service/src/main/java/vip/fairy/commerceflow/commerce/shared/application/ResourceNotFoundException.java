package vip.fairy.commerceflow.commerce.shared.application;

public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }
}
