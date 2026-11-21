package vip.fairy.commerceflow.commerce.invoice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class InvoiceJournal {

  private InvoiceJournal() {
  }

  public enum State {
    PENDING, ISSUED, EXHAUSTED, REVIEW_REQUIRED
  }

  public enum Kind {
    ISSUE, RED
  }

  public enum Outcome {
    PENDING, SUCCEEDED, FAILED
  }

  /** One durable customer application per order. */
  public record Application(
    long orderId,
    String applicationNo,
    long customerId,
    State status,
    String reviewReason,
    String reviewDetail,
    String orderNumber,
    long retryAfterId
  ) {
  }

  /** An immutable outbound command with its separately updated, authoritative platform result. */
  public record Attempt(
    long orderId,
    String requestId,
    Kind kind,
    String referenceNo,
    String paymentNo,
    String originalInvoiceNo,
    BigDecimal amount,
    String currency,
    Long merchantId,
    BigDecimal grossAmount,
    BigDecimal discountAmount,
    BigDecimal freightAmount,
    BigDecimal paidAmount,
    Outcome status,
    String invoiceNo,
    String failureCode
  ) {
  }

  /** Observed successful refund for the order's original payment, including any refunded freight. */
  public record RefundFact(
    long orderId,
    String refundNo,
    String paymentNo,
    BigDecimal amount,
    String currency,
    Instant succeededAt
  ) {
  }

  /** Customer-facing progress and effective balance, calculated only from confirmed tickets. */
  public record View(
    long orderId,
    String applicationNo,
    State status,
    String reviewReason,
    String reviewDetail,
    Attempt issue,
    List<String> frozenRefundNumbers,
    List<Attempt> redAttempts,
    BigDecimal effectiveAmount,
    String orderNumber
  ) {
  }
}
