package vip.fairy.commerceflow.simulator.payment.service;

public class LoginRateLimitException extends RuntimeException {
  public LoginRateLimitException(String message) {
    super(message);
  }
}
