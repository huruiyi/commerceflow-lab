package vip.fairy.commerceflow.simulator.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

final class InvoiceLedger {
  private InvoiceLedger() {
  }

  enum Kind {
    ISSUE, RED
  }

  enum Status {
    RECEIVED, ACCEPTED, SUCCEEDED, FAILED
  }

  enum Simulation {
    SUCCESS, FAIL, ACCEPT_THEN_TIMEOUT
  }

  record IssueInput(String applicationNo, String paymentNo, BigDecimal amount, String currency, Simulation simulation) {
  }

  record RedInput(String originalInvoiceNo, String refundNo, BigDecimal amount, String currency, Simulation simulation) {
  }

  /** Normalized identity and amount of one immutable submission attempt. */
  record Command(
    String requestId,
    Kind kind,
    String referenceNo,
    String paymentNo,
    String originalInvoiceNo,
    BigDecimal amount,
    String currency,
    Simulation simulation
  ) {
  }

  /**
   * Journal entry for one attempt. The reference is an application number for ISSUE or a refund number for RED.
   * Monetary amounts are positive, in the stated currency, with two decimal places.
   * Times are UTC. A ticket number exists only for SUCCEEDED and a failure code only for FAILED.
   */
  record Request(
    String requestId,
    Kind kind,
    String referenceNo,
    String paymentNo,
    String originalInvoiceNo,
    BigDecimal amount,
    String currency,
    Simulation simulation,
    Status status,
    String invoiceNo,
    String failureCode,
    LocalDateTime acceptedAt,
    LocalDateTime completedAt
  ) {
    Command command() {
      return new Command(requestId, kind, referenceNo, paymentNo, originalInvoiceNo, amount, currency, simulation);
    }

    Result result() {
      return new Result(requestId, kind, referenceNo, paymentNo, originalInvoiceNo, amount, currency, status,
        invoiceNo, failureCode, acceptedAt.toInstant(ZoneOffset.UTC),
        completedAt == null ? null : completedAt.toInstant(ZoneOffset.UTC));
    }
  }

  /** Immutable simulated ticket. Only RED tickets refer to an original ISSUE ticket. */
  record Ticket(
    String invoiceNo,
    String requestId,
    Kind kind,
    String originalInvoiceNo,
    BigDecimal amount,
    String currency,
    LocalDateTime issuedAt
  ) {
  }

  record Result(
    String requestId,
    Kind kind,
    String referenceNo,
    String paymentNo,
    String originalInvoiceNo,
    BigDecimal amount,
    String currency,
    Status status,
    String invoiceNo,
    String failureCode,
    Instant acceptedAt,
    Instant completedAt
  ) {
  }

  /** Receipt identity and retry state are persisted separately from the immutable terminal result. */
  record Receipt(String eventId, String requestId) {
  }
}
