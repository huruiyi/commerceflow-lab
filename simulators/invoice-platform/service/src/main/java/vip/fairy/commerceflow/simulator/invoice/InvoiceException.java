package vip.fairy.commerceflow.simulator.invoice;

import org.springframework.http.HttpStatus;

final class InvoiceException extends RuntimeException {
  private final HttpStatus status;

  InvoiceException(HttpStatus status, String code) {
    super(code);
    this.status = status;
  }

  HttpStatus status() {
    return status;
  }
}
