package vip.fairy.commerceflow.commerce.invoice.application;

import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Attempt;

import java.math.BigDecimal;
import java.time.Instant;

public interface InvoiceChannel {

  Result resolve(Attempt attempt);

  boolean authenticReceipt(String timestamp, String signature, String path, byte[] body);

  record Result(
    String requestId,
    String kind,
    String referenceNo,
    String paymentNo,
    String originalInvoiceNo,
    BigDecimal amount,
    String currency,
    String status,
    String invoiceNo,
    String failureCode,
    Instant acceptedAt,
    Instant completedAt
  ) {
  }

  class Rejected extends RuntimeException {

    public Rejected() {
      super("The invoice platform rejected the frozen command");
    }
  }
}
