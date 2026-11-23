package vip.fairy.commerceflow.commerce.invoice.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Application;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Attempt;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Kind;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Outcome;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.RefundFact;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.State;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.View;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.Eligibility;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.MerchantInvoice;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.OperatorInvoice;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.Page;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.Query;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.Ticket;
import vip.fairy.commerceflow.commerce.invoice.infrastructure.InvoiceJournalMapper;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.InvoiceOrderReadV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.InvoiceFundsV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "commerceflow.invoice", name = "enabled", havingValue = "true")
public class CommerceInvoiceService {

  private static final Logger log = LoggerFactory.getLogger(CommerceInvoiceService.class);
  private static final int CHANNEL_RECHECK_MS = 2000;
  private static final int REFUND_RECHECK_MS = 5000;
  private static final int FAILURE_RECHECK_MS = 5000;
  private static final int MAX_ATTEMPTS = 3;

  private final InvoiceOrderReadV1 orders;
  private final PaymentOrchestrationV1 payments;
  private final InvoiceChannel channel;
  private final InvoiceJournalMapper journal;
  private final TransactionTemplate transactions;

  public CommerceInvoiceService(InvoiceOrderReadV1 orders, PaymentOrchestrationV1 payments, InvoiceChannel channel,
                                InvoiceJournalMapper journal, TransactionTemplate transactions) {
    this.orders = orders;
    this.payments = payments;
    this.channel = channel;
    this.journal = journal;
    this.transactions = transactions;
  }

  public View request(long orderId, long customerId) {
    if (journal.find(orderId) == null) {
      var order = orders.find(customerId, orderId).orElseThrow(CommerceInvoiceService::missing);
      if (funds(orderId).paymentCount() == 0) {
        throw new BusinessRuleException("A successful payment is required to request an invoice");
      }
      journal.create(orderId, customerId, "APP-" + UUID.randomUUID(), order.orderNumber());
    }
    return get(orderId, customerId);
  }

  public View get(long orderId, long customerId) {
    return transactions.execute(ignored -> {
      Application app = journal.find(orderId);
      if (app == null || app.customerId() != customerId) {
        throw missing();
      }
      return view(app, journal.attempts(orderId));
    });
  }

  public Eligibility eligibility(long orderId, long customerId) {
    var order = orders.find(customerId, orderId).orElseThrow(CommerceInvoiceService::missing);
    return new Eligibility(order.orderNumber(), funds(orderId).paymentCount() > 0);
  }

  public Page search(Long customerId, Long merchantId, State status, String term, int page) {
    if (page < 0 || term != null && term.length() > 64) {
      throw new IllegalArgumentException("Invalid invoice search");
    }
    String filter = term == null || term.isBlank() ? null : term.strip();
    int size = 20;
    var rows = journal.search(new Query(customerId, merchantId, status, filter, size + 1, (long) page * size));
    return new Page(rows.stream().limit(size).toList(), page, rows.size() > size);
  }

  public MerchantInvoice merchantInvoice(long orderId, long merchantId) {
    return transactions.execute(ignored -> {
      Application app = requireApp(orderId);
      var attempts = journal.attempts(orderId);
      Attempt issue = latestIssue(attempts);
      if (!issued(issue) || !Objects.equals(issue.merchantId(), merchantId)) {
        throw missing();
      }
      var reds = attempts.stream().filter(attempt -> attempt.kind() == Kind.RED && issued(attempt)).toList();
      return new MerchantInvoice(orderId, app.orderNumber(), app.applicationNo(), app.status(),
        effectiveAmount(issue, reds), ticket(issue), reds.stream().map(CommerceInvoiceService::ticket).toList());
    });
  }

  public OperatorInvoice operatorInvoice(long orderId) {
    return transactions.execute(ignored -> operatorView(requireApp(orderId)));
  }

  public OperatorInvoice recheck(long orderId, long actorId, String requestId, String note) {
    return transactions.execute(ignored -> {
      Application app = journal.lock(orderId);
      if (app == null) {
        throw missing();
      }
      var prior = journal.reviewAction(orderId, requestId);
      if (prior != null) {
        if (prior.actorId() != actorId || !prior.note().equals(note)) {
          throw new BusinessRuleException("Invoice review request has different operator or reason");
        }
        return operatorView(app);
      }
      if (app.status() != State.REVIEW_REQUIRED) {
        throw new BusinessRuleException("Invoice is no longer awaiting review");
      }
      journal.insertReviewAction(orderId, actorId, app.reviewReason(), app.reviewDetail(), note, requestId);
      boolean restart = List.of("ISSUE_RETRIES_EXHAUSTED", "RED_RETRIES_EXHAUSTED").contains(app.reviewReason());
      State state = issued(latestIssue(journal.attempts(orderId))) ? State.ISSUED : State.PENDING;
      journal.reopen(orderId, state, restart);
      return operatorView(requireApp(orderId));
    });
  }

  private Application requireApp(long orderId) {
    Application app = journal.find(orderId);
    if (app == null) {
      throw missing();
    }
    return app;
  }

  private OperatorInvoice operatorView(Application app) {
    var attempts = journal.attempts(app.orderId());
    return new OperatorInvoice(app.orderNumber(), view(app, attempts),
      attempts.stream().filter(a -> a.kind() == Kind.ISSUE).toList(), journal.reviewActions(app.orderId()));
  }

  private View view(Application app, List<Attempt> attempts) {
    Attempt issue = latestIssue(attempts);
    var reds = attempts.stream().filter(attempt -> attempt.kind() == Kind.RED).toList();
    return new View(app.orderId(), app.applicationNo(), app.status(), app.reviewReason(), app.reviewDetail(), issue,
      issue == null ? List.of() : journal.includedRefunds(issue.requestId()), reds, effectiveAmount(issue, reds),
      app.orderNumber());
  }

  private static BigDecimal effectiveAmount(Attempt issue, List<Attempt> reds) {
    if (!issued(issue)) {
      return null;
    }
    var refunded = reds.stream().filter(CommerceInvoiceService::issued).map(Attempt::amount)
      .reduce(BigDecimal.ZERO, BigDecimal::add);
    return issue.amount().subtract(refunded);
  }

  private static boolean issued(Attempt attempt) {
    return attempt != null && attempt.status() == Outcome.SUCCEEDED;
  }

  private static Ticket ticket(Attempt attempt) {
    return new Ticket(attempt.invoiceNo(), attempt.referenceNo(), attempt.amount(), attempt.currency());
  }

  public void nudge(String requestId) {
    journal.nudge(requestId);
  }

  @Scheduled(fixedDelay = 500)
  void reconcile() {
    for (long orderId : journal.due()) {
      try {
        Attempt attempt = transactions.execute(ignored -> plan(orderId));
        if (attempt != null) {
          resolve(attempt);
        }
      } catch (RuntimeException ex) {
        log.warn("Invoice reconciliation deferred for order {} ({})", orderId, ex.getClass().getSimpleName());
        transactions.executeWithoutResult(ignored -> {
          Application app = journal.lockDue(orderId);
          if (app != null) {
            journal.advance(orderId, app.status(), FAILURE_RECHECK_MS);
          }
        });
      }
    }
  }

  private Attempt plan(long orderId) {
    Application app = journal.lockDue(orderId);
    if (app == null) {
      return null;
    }
    try {
      var attempts = journal.attempts(orderId);
      Attempt issue = latestIssue(attempts);
      InvoiceFundsV1 funds = funds(orderId);
      if (issue != null && (!issue.paymentNo().equals(funds.paymentNumber()) ||
        !issue.currency().equals(funds.currency()) || !InvoiceFacts.same(issue.paidAmount(), funds.paidAmount()))) {
        throw new InvoiceFacts.Discrepancy("ORIGINAL_PAYMENT_CHANGED",
          "frozenPayment=%s frozenAmount=%s frozenCurrency=%s observedPayment=%s observedAmount=%s observedCurrency=%s"
            .formatted(issue.paymentNo(), issue.paidAmount(), issue.currency(), funds.paymentNumber(),
              funds.paidAmount(), funds.currency()));
      }
      var previous = journal.refunds(orderId);
      var observed = InvoiceFacts.reconcile(orderId, funds, previous);
      var known = previous.stream().map(RefundFact::refundNo).collect(Collectors.toSet());
      observed.stream().filter(refund -> !known.contains(refund.refundNo())).forEach(journal::observe);
      var pending = attempts.stream().filter(attempt -> attempt.status() == Outcome.PENDING).findFirst();
      if (pending.isPresent()) {
        return awaitChannel(app, pending.get());
      }
      if (issue == null || issue.status() == Outcome.FAILED) {
        return planIssue(app, funds, observed);
      }
      return planRed(app, issue, observed, attempts);
    } catch (InvoiceFacts.Discrepancy ex) {
      journal.review(orderId, ex.getMessage(), ex.detail());
      return null;
    }
  }

  private Attempt planIssue(Application app, InvoiceFundsV1 funds, List<RefundFact> refunds) {
    var order = orders.find(app.customerId(), app.orderId())
      .orElseThrow(() -> new InvoiceFacts.Discrepancy("ORDER_OWNERSHIP_CHANGED"));
    var amounts = InvoiceFacts.orderAmounts(order, funds);
    var amount = funds.paidAmount().subtract(funds.refundedAmount());
    if (amount.signum() == 0) {
      journal.advance(app.orderId(), State.EXHAUSTED, null);
      return null;
    }
    if (journal.attemptCount(app.orderId(), Kind.ISSUE, app.applicationNo(), app.retryAfterId()) >= MAX_ATTEMPTS) {
      throw new InvoiceFacts.Discrepancy("ISSUE_RETRIES_EXHAUSTED");
    }
    var issue = new Attempt(app.orderId(), requestId(), Kind.ISSUE, app.applicationNo(), funds.paymentNumber(),
      null, amount, funds.currency(), amounts.merchantId(), amounts.gross(), amounts.discount(), amounts.freight(),
      funds.paidAmount(), Outcome.PENDING, null, null);
    journal.insertAttempt(issue);
    refunds.forEach(refund -> journal.includeRefund(issue.requestId(), app.orderId(), refund.refundNo()));
    return awaitChannel(app, issue);
  }

  private Attempt planRed(Application app, Attempt issue, List<RefundFact> refunds, List<Attempt> attempts) {
    var included = journal.includedRefunds(issue.requestId());
    var redAttempts = attempts.stream().filter(attempt -> attempt.kind() == Kind.RED)
      .collect(Collectors.groupingBy(Attempt::referenceNo));
    BigDecimal remaining = issue.amount();
    for (var refund : refunds) {
      if (included.contains(refund.refundNo())) {
        continue;
      }
      var history = redAttempts.getOrDefault(refund.refundNo(), List.of());
      remaining = remaining.subtract(refund.amount());
      if (remaining.signum() < 0) {
        throw new InvoiceFacts.Discrepancy("RED_AMOUNT_EXCEEDS_AVAILABLE");
      }
      if (history.stream().anyMatch(attempt -> attempt.status() == Outcome.SUCCEEDED)) {
        continue;
      }
      if (journal.attemptCount(app.orderId(), Kind.RED, refund.refundNo(), app.retryAfterId()) >= MAX_ATTEMPTS) {
        throw new InvoiceFacts.Discrepancy("RED_RETRIES_EXHAUSTED");
      }
      var red = new Attempt(app.orderId(), requestId(), Kind.RED, refund.refundNo(), issue.paymentNo(),
        issue.invoiceNo(), refund.amount(), refund.currency(), null, null, null, null, null, Outcome.PENDING,
        null, null);
      journal.insertAttempt(red);
      return awaitChannel(app, red);
    }
    journal.advance(app.orderId(), State.ISSUED, remaining.signum() == 0 ? null : REFUND_RECHECK_MS);
    return null;
  }

  private Attempt awaitChannel(Application app, Attempt attempt) {
    journal.advance(app.orderId(), app.status(), CHANNEL_RECHECK_MS);
    return attempt;
  }

  private void resolve(Attempt attempt) {
    InvoiceChannel.Result result;
    try {
      result = channel.resolve(attempt);
    } catch (InvoiceChannel.Rejected ex) {
      reviewPending(attempt, "CHANNEL_COMMAND_REJECTED", ex.getMessage());
      return;
    }
    if (!matches(attempt, result)) {
      reviewPending(attempt, "CHANNEL_RESULT_MISMATCH", "request=" + attempt.requestId() + " result=" + result);
      return;
    }
    if ("ACCEPTED".equals(result.status())) {
      return;
    }
    boolean success = "SUCCEEDED".equals(result.status()) && result.invoiceNo() != null;
    boolean failure = "FAILED".equals(result.status()) && result.invoiceNo() == null && result.failureCode() != null;
    if (!success && !failure) {
      reviewPending(attempt, "CHANNEL_RESULT_MISMATCH", "request=" + attempt.requestId() + " result=" + result);
      return;
    }
    transactions.executeWithoutResult(ignored -> {
      Application app = journal.lock(attempt.orderId());
      if (app.status() == State.REVIEW_REQUIRED ||
        journal.finish(attempt.requestId(), success ? Outcome.SUCCEEDED : Outcome.FAILED,
          result.invoiceNo(), result.failureCode()) == 0) {
        return;
      }
      State status = attempt.kind() == Kind.ISSUE && success ? State.ISSUED : app.status();
      journal.advance(app.orderId(), status, success ? 0 : FAILURE_RECHECK_MS);
    });
  }

  private void reviewPending(Attempt attempt, String reason, String detail) {
    transactions.executeWithoutResult(ignored -> {
      Application app = journal.lock(attempt.orderId());
      if (app.status() != State.REVIEW_REQUIRED &&
        journal.attempts(app.orderId()).stream().anyMatch(candidate ->
          candidate.requestId().equals(attempt.requestId()) && candidate.status() == Outcome.PENDING)) {
        journal.review(app.orderId(), reason, detail);
      }
    });
  }

  private static boolean matches(Attempt attempt, InvoiceChannel.Result result) {
    String expectedPayment = attempt.kind() == Kind.ISSUE ? attempt.paymentNo() : null;
    return result != null && attempt.requestId().equals(result.requestId()) &&
      attempt.kind().name().equals(result.kind()) && attempt.referenceNo().equals(result.referenceNo()) &&
      Objects.equals(expectedPayment, result.paymentNo()) &&
      Objects.equals(attempt.originalInvoiceNo(), result.originalInvoiceNo()) &&
      InvoiceFacts.same(attempt.amount(), result.amount()) && attempt.currency().equals(result.currency());
  }

  private InvoiceFundsV1 funds(long orderId) {
    return payments.invoiceFunds(new PaymentQueryV1(PaymentQueryV1.VERSION, orderId));
  }

  private static Attempt latestIssue(List<Attempt> attempts) {
    return attempts.reversed().stream().filter(attempt -> attempt.kind() == Kind.ISSUE).findFirst().orElse(null);
  }

  private static String requestId() {
    return "REQ-" + UUID.randomUUID();
  }

  private static ResourceNotFoundException missing() {
    return new ResourceNotFoundException("Invoice or order was not found");
  }
}
