package vip.fairy.commerceflow.commerce.invoice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Attempt;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.State;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.View;

public final class InvoicePortal {

  private InvoicePortal() {
  }

  public record Eligibility(String orderNumber, boolean eligible) {
  }

  /** Scope is set by the authenticated endpoint. At most one customer or merchant is selected. */
  public record Query(Long customerId, Long merchantId, State status, String term, int limit, long offset) {
  }

  public record Summary(long orderId, String orderNumber, String applicationNo, State status, Long merchantId,
                        String invoiceNo, BigDecimal amount, String currency, BigDecimal effectiveAmount) {
  }

  public record Page(List<Summary> rows, int page, boolean hasNext) {
  }

  public record Ticket(String invoiceNo, String referenceNo, BigDecimal amount, String currency) {
  }

  public record MerchantInvoice(long orderId, String orderNumber, String applicationNo, State status,
                                BigDecimal effectiveAmount, Ticket issue, List<Ticket> redTickets) {
  }

  /** A completed operator action retains the previous review evidence and the authenticated actor. */
  public record ReviewAction(long actorId, String priorReason, String priorDetail, String note, Instant createdAt) {
  }

  public record OperatorInvoice(String orderNumber, View invoice, List<Attempt> issueAttempts,
                                List<ReviewAction> actions) {
  }
}
