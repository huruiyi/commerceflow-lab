package vip.fairy.commerceflow.commerce.invoice.application;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.commerce.invoice.application.contract.v1.SettlementInvoiceV1;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Attempt;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Kind;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Outcome;
import vip.fairy.commerceflow.commerce.invoice.infrastructure.InvoiceJournalMapper;

@Component
public class SettlementInvoiceQuery implements SettlementInvoiceV1 {

  private final InvoiceJournalMapper journal;

  SettlementInvoiceQuery(InvoiceJournalMapper journal) {
    this.journal = journal;
  }

  @Override
  public Optional<Tickets> confirmed(long merchantId, long orderId) {
    var confirmed = journal.attempts(orderId).stream()
      .filter(attempt -> attempt.status() == Outcome.SUCCEEDED).toList();
    return confirmed.stream()
      .filter(attempt -> attempt.kind() == Kind.ISSUE && Long.valueOf(merchantId).equals(attempt.merchantId()))
      .findFirst()
      .map(issue -> new Tickets(ticket(issue), journal.includedRefunds(issue.requestId()),
        confirmed.stream().filter(attempt -> attempt.kind() == Kind.RED).map(SettlementInvoiceQuery::ticket).toList()));
  }

  @Override
  public boolean reviewed(long merchantId, long orderId, String requestId, Instant reviewedAt) {
    var action = journal.reviewAction(orderId, requestId);
    return action != null && !action.createdAt().isBefore(reviewedAt)
      && journal.attempts(orderId).stream().anyMatch(attempt -> attempt.kind() == Kind.ISSUE
        && Long.valueOf(merchantId).equals(attempt.merchantId()));
  }

  private static Ticket ticket(Attempt attempt) {
    return new Ticket(attempt.invoiceNo(), attempt.originalInvoiceNo(), attempt.referenceNo(), attempt.paymentNo(),
      attempt.amount(), attempt.currency());
  }
}
