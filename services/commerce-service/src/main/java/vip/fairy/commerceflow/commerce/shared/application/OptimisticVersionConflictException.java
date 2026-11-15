package vip.fairy.commerceflow.commerce.shared.application;

public class OptimisticVersionConflictException extends RuntimeException {

  private final String errorCode;

  public OptimisticVersionConflictException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
