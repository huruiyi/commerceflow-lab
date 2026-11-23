package vip.fairy.commerceflow.commerce.merchant.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.invoice.application.contract.v1.SettlementInvoiceV1;
import vip.fairy.commerceflow.commerce.invoice.application.contract.v1.SettlementInvoiceV1.Tickets;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementRefundV1;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Account;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Entry;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Rollup;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Statement;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.MerchantPayoutMapper;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.SettlementMapper;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.SettlementOrderV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.InvoiceFundsV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class StatementReconciliationService {

  private final SettlementMapper ledger;
  private final MerchantPayoutMapper payouts;
  private final SettlementOrderV1 orders;
  private final SettlementRefundV1 afterSales;
  private final PaymentOrchestrationV1 payments;
  private final SettlementInvoiceV1 invoices;

  StatementReconciliationService(SettlementMapper ledger, MerchantPayoutMapper payouts, SettlementOrderV1 orders,
                                 SettlementRefundV1 afterSales, PaymentOrchestrationV1 payments,
                                 SettlementInvoiceV1 invoices) {
    this.ledger = ledger;
    this.payouts = payouts;
    this.orders = orders;
    this.afterSales = afterSales;
    this.payments = payments;
    this.invoices = invoices;
  }

  @Transactional(readOnly = true)
  public Report reconcile(long merchant, String statementId, int requestedPage, int size) {
    if (requestedPage < 0 || size < 1 || size > 20) {
      throw new IllegalArgumentException("Invalid reconciliation page");
    }
    var statement = ledger.statement(statementId);
    if (statement == null || !Long.valueOf(merchant).equals(statement.merchantId())) {
      throw new ResourceNotFoundException("Statement not found");
    }
    var rollup = ledger.statementRollup(statementId, merchant);
    int page = rollup.orderCount() == 0 ? 0 : (int) Math.min(requestedPage, (rollup.orderCount() - 1) / size);
    var differences = statementDifferences(statement, rollup);
    var account = ledger.account(merchant);
    if (account == null) differences.add(new Difference("SETTLEMENT_ACCOUNT_MISSING", statementId, null, null));
    var payoutRefs = payouts.attempts(statementId).stream()
      .filter(attempt -> Long.valueOf(merchant).equals(attempt.merchantId()))
      .map(attempt -> new Payout(attempt.id(), attempt.channelPayoutNo(), attempt.status(),
        attempt.amount(), attempt.currency()))
      .toList();
    var settled = payoutRefs.stream().filter(payout -> "SUCCEEDED".equals(payout.status())).toList();
    var paidOut = settled.stream().map(Payout::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (!settled.isEmpty()) {
      compare(differences, "PAYOUT_AMOUNT", statementId, statement.payable(), paidOut);
      if (settled.size() > 1) differences.add(new Difference("MULTIPLE_PAYOUTS", statementId, null, paidOut));
      settled.stream().filter(payout -> !"CNY".equals(payout.currency()))
        .forEach(payout -> differences.add(new Difference("PAYOUT_CURRENCY", payout.id(), null, payout.amount())));
    }
    var orderIds = ledger.statementOrderIds(statementId, merchant, (long) page * size, size);
    List<OrderCheck> checks = new ArrayList<>();
    if (!orderIds.isEmpty()) {
      var entries = ledger.statementOrderEntries(statementId, merchant, orderIds).stream()
        .collect(Collectors.groupingBy(Entry::orderId));
      for (var orderId : orderIds) checks.add(checkOrder(merchant, orderId, account, entries.get(orderId)));
    }
    return new Report(statement, rollup.payable(), paidOut, payoutRefs, differences, checks,
      rollup.orderCount(), page, size, Instant.now());
  }

  private List<Difference> statementDifferences(Statement statement, Rollup rollup) {
    List<Difference> differences = new ArrayList<>();
    compare(differences, "STATEMENT_MERCHANDISE", statement.id(), statement.merchandise(), rollup.merchandise());
    compare(differences, "STATEMENT_FREIGHT", statement.id(), statement.freight(), rollup.freight());
    compare(differences, "STATEMENT_COMMISSION", statement.id(), statement.commission(), rollup.commission());
    compare(differences, "STATEMENT_PAYABLE", statement.id(), statement.payable(), rollup.payable());
    return differences;
  }

  private OrderCheck checkOrder(long merchant, long orderId, Account account, List<Entry> entries) {
    var sourceKeys = entries.stream().map(Entry::sourceKey).toList();
    var payable = entries.stream().map(entry -> entry.merchandise().add(entry.freight()).subtract(entry.commission()))
      .reduce(BigDecimal.ZERO, BigDecimal::add);
    var order = orders.find(merchant, orderId).orElse(null);
    var lines = order == null ? List.<SettlementOrderV1.Line>of() : orders.lines(orderId);
    if (lines.isEmpty() || lines.stream().anyMatch(line -> !Long.valueOf(merchant).equals(line.merchant()))) {
      var missing = new Difference(order == null ? "ORDER_MISSING" : "ORDER_SCOPE", Long.toString(orderId), null, null);
      return new OrderCheck(orderId, payable, null, null, null, null, null, null, List.of(), List.of(), sourceKeys,
        List.of(missing));
    }
    var funds = payments.invoiceFunds(new PaymentQueryV1(PaymentQueryV1.VERSION, orderId));
    List<Difference> differences = new ArrayList<>();
    String paymentRef = funds.paymentNumber() == null ? Long.toString(orderId) : funds.paymentNumber();
    compare(differences, "PAYMENT_AMOUNT", paymentRef, order.total(), funds.paidAmount());
    if (funds.paymentCount() != 1 || !Objects.equals(order.currency(), funds.currency())) {
      differences.add(new Difference("PAYMENT_FACTS", paymentRef, null, funds.paidAmount()));
    }
    var refunds = new HashMap<String, InvoiceFundsV1.Refund>();
    for (var refund : funds.refunds()) {
      if (!Objects.equals(refund.orderId(), orderId) || !Objects.equals(order.currency(), refund.currency())
          || refunds.putIfAbsent(refund.refundNumber(), refund) != null) {
        differences.add(new Difference("REFUND_FACTS", refund.refundNumber(), null, refund.amount()));
      }
    }
    var observedRefunds = refunds.values().stream().map(InvoiceFundsV1.Refund::amount)
      .reduce(BigDecimal.ZERO, BigDecimal::add);
    compare(differences, "PAYMENT_REFUND_TOTAL", paymentRef, funds.refundedAmount(), observedRefunds);
    checkEarnings(order, lines, account, entries, differences);
    checkReversals(merchant, orderId, entries, refunds, differences);
    var tickets = invoices.confirmed(merchant, orderId).orElse(null);
    BigDecimal netInvoice = tickets == null ? null : checkInvoice(tickets, funds, refunds, differences);
    var redNumbers = tickets == null ? List.<String>of()
      : tickets.reds().stream().map(SettlementInvoiceV1.Ticket::invoiceNumber).filter(Objects::nonNull).toList();
    return new OrderCheck(orderId, payable, order.total(), funds.paidAmount(), funds.refundedAmount(),
      funds.paymentNumber(), tickets == null ? null : tickets.issue().invoiceNumber(), netInvoice, redNumbers,
      refunds.keySet().stream().sorted().toList(), sourceKeys, differences);
  }

  private void checkEarnings(SettlementOrderV1.Order order, List<SettlementOrderV1.Line> snapshot, Account account,
                             List<Entry> entries, List<Difference> differences) {
    var lines = snapshot.stream()
      .collect(Collectors.toMap(SettlementOrderV1.Line::id, line -> line));
    for (var entry : entries) {
      if ("FREIGHT".equals(entry.kind())) {
        compare(differences, "FREIGHT_ENTRY", entry.sourceKey(), order.freight(), entry.freight());
        compare(differences, "COMMISSION_ENTRY", entry.sourceKey(), BigDecimal.ZERO, entry.commission());
      } else if ("MERCHANDISE".equals(entry.kind())) {
        var line = lines.get(entry.orderLineId());
        compare(differences, "MERCHANDISE_ENTRY", entry.sourceKey(),
          line == null ? null : line.merchandise(), entry.merchandise());
        if (line != null && account != null) {
          compare(differences, "COMMISSION_ENTRY", entry.sourceKey(), account.commission(line.merchandise()),
            entry.commission());
        }
      }
    }
  }

  private void checkReversals(long merchant, long orderId, List<Entry> entries,
                              Map<String, InvoiceFundsV1.Refund> refunds, List<Difference> differences) {
    var reversals = entries.stream().filter(entry -> "REFUND".equals(entry.kind())).toList();
    if (reversals.isEmpty()) return;
    var cases = afterSales.evidence(merchant, orderId).stream()
      .collect(Collectors.toMap(value -> "refund:" + value.id(), value -> value));
    for (var entry : reversals) {
      var source = cases.get(entry.sourceKey());
      var refund = source == null || !"COMPLETED".equals(source.status()) ? null : refunds.get(source.number());
      var expected = entry.merchandise().add(entry.freight()).negate();
      compare(differences, "REFUND_REVERSAL", entry.sourceKey(), expected, refund == null ? null : refund.amount());
      if (source != null && (source.merchandise().compareTo(entry.merchandise().negate()) != 0
          || source.freight().compareTo(entry.freight().negate()) != 0)) {
        differences.add(new Difference("REFUND_ALLOCATION", entry.sourceKey(), null, expected));
      }
    }
  }

  private BigDecimal checkInvoice(Tickets tickets, InvoiceFundsV1 funds,
                                  Map<String, InvoiceFundsV1.Refund> refunds, List<Difference> differences) {
    var blue = tickets.issue();
    var covered = new HashSet<String>();
    BigDecimal originalDeductions = BigDecimal.ZERO;
    for (var reference : tickets.includedRefunds()) {
      var refund = refunds.get(reference);
      if (refund == null || !covered.add(reference)) {
        differences.add(new Difference("BLUE_REFUND_REFERENCE", reference, null, null));
      } else {
        originalDeductions = originalDeductions.add(refund.amount());
      }
    }
    var expectedBlue = funds.paidAmount() == null ? null : funds.paidAmount().subtract(originalDeductions);
    compare(differences, "BLUE_AMOUNT", blue.invoiceNumber(), expectedBlue, blue.amount());
    if (!Objects.equals(blue.paymentNumber(), funds.paymentNumber())
        || !Objects.equals(blue.currency(), funds.currency())) {
      differences.add(new Difference("BLUE_FACTS", blue.invoiceNumber(), null, blue.amount()));
    }
    BigDecimal redTotal = BigDecimal.ZERO;
    for (var red : tickets.reds()) {
      var refund = refunds.get(red.reference());
      if (!covered.add(red.reference()) || !Objects.equals(blue.invoiceNumber(), red.originalInvoiceNumber())
          || !Objects.equals(blue.paymentNumber(), red.paymentNumber())
          || !Objects.equals(blue.currency(), red.currency())) {
        differences.add(new Difference("RED_FACTS", red.invoiceNumber(), null, red.amount()));
      }
      compare(differences, "RED_AMOUNT", red.invoiceNumber(), refund == null ? null : refund.amount(), red.amount());
      redTotal = redTotal.add(red.amount());
    }
    refunds.values().stream().filter(refund -> !covered.contains(refund.refundNumber()))
      .forEach(refund -> differences.add(new Difference("RED_NOT_CONFIRMED", refund.refundNumber(),
        refund.amount(), null)));
    return blue.amount().subtract(redTotal);
  }

  private static void compare(List<Difference> differences, String kind, String reference,
                              BigDecimal expected, BigDecimal observed) {
    if (expected == null || observed == null || expected.compareTo(observed) != 0) {
      differences.add(new Difference(kind, reference, expected, observed));
    }
  }

  public record Difference(String kind, String reference, BigDecimal expected, BigDecimal observed) {
  }

  public record Payout(String id, String channelPayoutNo, String status, BigDecimal amount, String currency) {
  }

  public record OrderCheck(long orderId, BigDecimal statementPayable, BigDecimal orderAmount, BigDecimal paidAmount,
                           BigDecimal refundedAmount, String paymentNumber, String blueInvoiceNumber,
                           BigDecimal netInvoice, List<String> redInvoiceNumbers, List<String> refundNumbers,
                           List<String> entryReferences,
                           List<Difference> differences) {
  }

  public record Report(Statement statement, BigDecimal entryPayable, BigDecimal paidOut, List<Payout> payouts,
                       List<Difference> differences, List<OrderCheck> orders, long total, int page, int size,
                       Instant observedAt) {
  }
}
