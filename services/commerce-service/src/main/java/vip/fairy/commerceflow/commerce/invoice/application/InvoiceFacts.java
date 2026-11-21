package vip.fairy.commerceflow.commerce.invoice.application;

import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.RefundFact;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.InvoiceOrderReadV1.Line;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.InvoiceOrderReadV1.Order;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.InvoiceFundsV1;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class InvoiceFacts {

  private InvoiceFacts() {
  }

  static List<RefundFact> reconcile(long orderId, InvoiceFundsV1 funds, List<RefundFact> previous) {
    if (funds.paymentCount() != 1 || funds.paidAt() == null || funds.paidAmount() == null ||
      funds.paidAmount().signum() <= 0 || funds.paymentNumber() == null ||
      funds.currency() == null || funds.refunds() == null) {
      throw new Discrepancy("PAYMENT_EVIDENCE_MISMATCH");
    }
    Map<String, RefundFact> old = previous.stream().collect(Collectors.toMap(RefundFact::refundNo, value -> value));
    var observed = funds.refunds().stream().map(refund -> {
      if (refund.orderId() == null || refund.orderId() != orderId || refund.amount() == null ||
        refund.amount().signum() <= 0 || refund.refundNumber() == null || refund.succeededAt() == null ||
        !funds.currency().equals(refund.currency())) {
        throw new Discrepancy("REFUND_EVIDENCE_MISMATCH",
          "refund=%s order=%s expectedOrder=%s amount=%s currency=%s expectedCurrency=%s succeededAt=%s"
            .formatted(refund.refundNumber(), refund.orderId(), orderId, refund.amount(), refund.currency(),
              funds.currency(), refund.succeededAt()));
      }
      var fact = new RefundFact(orderId, refund.refundNumber(), funds.paymentNumber(),
        refund.amount(), refund.currency(), refund.succeededAt());
      RefundFact prior = old.remove(fact.refundNo());
      if (prior != null && !prior.equals(fact)) {
        throw new Discrepancy("REFUND_EVIDENCE_CHANGED", "stored=%s observed=%s".formatted(prior, fact));
      }
      return fact;
    }).toList();
    BigDecimal refunded = observed.stream().map(RefundFact::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    long distinctRefunds = observed.stream().map(RefundFact::refundNo).distinct().count();
    if (!old.isEmpty() || distinctRefunds != observed.size() ||
      !same(refunded, funds.refundedAmount()) || refunded.compareTo(funds.paidAmount()) > 0) {
      throw new Discrepancy("REFUND_LEDGER_MISMATCH",
        "payment=%s paid=%s refunded=%s successfulRefundSum=%s uniqueRefunds=%s observedRefunds=%s missing=%s"
          .formatted(funds.paymentNumber(), funds.paidAmount(), funds.refundedAmount(), refunded,
            distinctRefunds, observed.size(), old.keySet()));
    }
    return observed;
  }

  static OrderAmounts orderAmounts(Order order, InvoiceFundsV1 funds) {
    List<Line> lines = order.lines();
    if (lines.isEmpty() || lines.stream().anyMatch(line -> line.merchantId() == null || line.merchantId() <= 0 ||
      !line.merchantId().equals(lines.getFirst().merchantId()))) {
      var merchants = lines.stream().map(Line::merchantId).distinct().toList();
      throw new Discrepancy("ORDER_MERCHANT_MISMATCH", "merchantIds=" + merchants);
    }
    if (lines.stream().anyMatch(line -> line.gross() == null || line.discount() == null ||
      line.gross().signum() < 0 || line.discount().signum() < 0 || line.discount().compareTo(line.gross()) > 0)) {
      throw new Discrepancy("ORDER_LINE_AMOUNT_MISMATCH");
    }
    BigDecimal gross = lines.stream().map(Line::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal discount = lines.stream().map(Line::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal freight = order.freight();
    if (freight == null || freight.signum() < 0 || !same(gross, order.gross()) ||
      !same(discount, order.discount()) || !same(order.total(), funds.paidAmount()) ||
      !same(gross.subtract(discount).add(freight), funds.paidAmount()) ||
      !funds.currency().equals(order.currency())) {
      throw new Discrepancy("ORDER_PAYMENT_AMOUNT_MISMATCH",
        "gross=%s discount=%s freight=%s orderTotal=%s orderCurrency=%s paid=%s paymentCurrency=%s payment=%s"
          .formatted(gross, discount, freight, order.total(), order.currency(), funds.paidAmount(), funds.currency(),
            funds.paymentNumber()));
    }
    return new OrderAmounts(lines.getFirst().merchantId(), gross, discount, freight);
  }

  static boolean same(BigDecimal first, BigDecimal second) {
    return first != null && second != null && first.compareTo(second) == 0;
  }

  record OrderAmounts(Long merchantId, BigDecimal gross, BigDecimal discount, BigDecimal freight) {
  }

  static final class Discrepancy extends RuntimeException {

    private final String detail;

    Discrepancy(String reason) {
      this(reason, null);
    }

    Discrepancy(String reason, String detail) {
      super(reason);
      this.detail = detail;
    }

    String detail() {
      return detail;
    }
  }
}
