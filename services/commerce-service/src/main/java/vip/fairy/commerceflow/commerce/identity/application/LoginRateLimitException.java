package vip.fairy.commerceflow.commerce.identity.application;

public class LoginRateLimitException extends RuntimeException {
  public LoginRateLimitException() {
    super("Too many failed login attempts. Please try again in a few minutes.");
  }
}
