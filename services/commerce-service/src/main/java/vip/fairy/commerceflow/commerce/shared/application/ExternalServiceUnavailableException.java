package vip.fairy.commerceflow.commerce.shared.application;

public class ExternalServiceUnavailableException extends RuntimeException {

  private final String errorCode;

  public ExternalServiceUnavailableException(String errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
