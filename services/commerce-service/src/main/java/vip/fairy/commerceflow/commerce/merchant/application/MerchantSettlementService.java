package vip.fairy.commerceflow.commerce.merchant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Account;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Entry;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Statement;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.MerchantStoreMapper;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.SettlementMapper;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.SettlementOrderV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundQueryV1;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementRefundV1;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class MerchantSettlementService {

  private static final BigDecimal ZERO = new BigDecimal("0.00");
  private static final Set<String> CLOSED_CASES = Set.of("FAILED", "REJECTED", "COMPLETED");

  private final SettlementMapper ledger;
  private final MerchantStoreMapper stores;
  private final SettlementOrderV1 orders;
  private final SettlementRefundV1 afterSales;
  private final PaymentOrchestrationV1 payments;

  public MerchantSettlementService(SettlementMapper ledger, MerchantStoreMapper stores,
                                   SettlementOrderV1 orders, SettlementRefundV1 afterSales,
                                   PaymentOrchestrationV1 payments) {
    this.ledger = ledger;
    this.stores = stores;
    this.orders = orders;
    this.afterSales = afterSales;
    this.payments = payments;
  }

  @Transactional
  public Account enable(Long merchant, int rate) {
    if (stores.lock(merchant) == null) throw new ResourceNotFoundException("Store not found");
    var account = ledger.lockAccount(merchant);
    if (account != null) {
      if (account.commissionBps() != rate) throw new BusinessRuleException("The commission rate is frozen");
      return account;
    }
    ledger.enable(merchant, rate);
    return ledger.lockAccount(merchant);
  }

  @Transactional
  public Statement close(Long merchant, String requestId, Long admin) {
    var account = ledger.lockAccount(merchant);
    if (account == null) throw new BusinessRuleException("Set up the merchant settlement account first");
    var replay = ledger.statement(requestId);
    if (replay != null) {
      if (!merchant.equals(replay.merchantId())) throw new BusinessRuleException("Request belongs to another store");
      return replay;
    }
    Instant cutoff = ledger.now();
    var verified = new HashMap<Long, Set<Long>>();
    for (var order : orders.completed(merchant, account.enabledAt(), cutoff)) {
      Set<Long> held = accrue(account, order, cutoff);
      if (held != null) verified.put(order.id(), held);
    }
    var pending = ledger.pending(merchant).stream().filter(entry -> {
      var held = verified.get(entry.orderId());
      return held != null && (entry.orderLineId() == null ? held.isEmpty() : !held.contains(entry.orderLineId()));
    }).toList();
    BigDecimal merchandise = pending.stream().map(Entry::merchandise).reduce(ZERO, BigDecimal::add);
    BigDecimal freight = pending.stream().map(Entry::freight).reduce(ZERO, BigDecimal::add);
    BigDecimal commission = pending.stream().map(Entry::commission).reduce(ZERO, BigDecimal::add);
    BigDecimal payable = merchandise.add(freight).subtract(commission);
    if (payable.signum() <= 0) return null;

    Instant previous = ledger.lastCutoff(merchant);
    var statement = new Statement(requestId, merchant, previous == null ? account.enabledAt() : previous,
      cutoff, merchandise, freight, commission, payable, admin);
    ledger.freeze(statement);
    if (ledger.assign(merchant, requestId, pending.stream().map(Entry::sourceKey).toList()) != pending.size()) {
      throw new IllegalStateException("Ledger changed");
    }
    return statement;
  }

  public List<Statement> statements(Long merchant) {
    return ledger.statements(merchant);
  }

  public List<Entry> pending(Long merchant) {
    return ledger.pending(merchant);
  }

  public List<Entry> entries(Long merchant, String statement) {
    var value = ledger.statement(statement);
    if (value == null || !merchant.equals(value.merchantId())) {
      throw new ResourceNotFoundException("Statement not found");
    }
    return ledger.statementEntries(statement);
  }

  private Set<Long> accrue(Account account, SettlementOrderV1.Order order, Instant cutoff) {
    if (!"CNY".equals(order.currency())) throw new IllegalStateException("Unsupported settlement currency");
    var lines = orders.lines(order.id());
    if (lines.isEmpty() || lines.stream().anyMatch(line -> !account.merchantId().equals(line.merchant()))) {
      throw new IllegalStateException("Order does not have single-merchant settlement ownership");
    }
    var cases = afterSales.lockEvidence(order.id());
    var refunds = successfulRefunds(order, cases, cutoff);
    var payment = payments.latest(new PaymentQueryV1(PaymentQueryV1.VERSION, order.id())).orElse(null);
    if (payment == null || !"SUCCESS".equals(payment.status()) || !"CNY".equals(payment.currency())
      || payment.amount().compareTo(order.total()) != 0) {
      throw new IllegalStateException("Order payment does not reconcile");
    }
    BigDecimal confirmed = refunds.stream().map(refund -> refund.evidence().merchandise()
      .add(refund.evidence().freight())).reduce(ZERO, BigDecimal::add);
    BigDecimal paidRefunds = payment.refundedAmount() == null ? ZERO : payment.refundedAmount();
    if (confirmed.compareTo(paidRefunds) != 0) return null;

    var prior = ledger.orderEntries(order.id());
    var keys = new HashSet<String>();
    var net = new HashMap<Long, BigDecimal>();
    for (var entry : prior) {
      keys.add(entry.sourceKey());
      if (entry.orderLineId() != null) net.merge(entry.orderLineId(), entry.merchandise(), BigDecimal::add);
    }
    var held = new HashSet<Long>();
    cases.stream().filter(value -> !CLOSED_CASES.contains(value.status())).forEach(value -> held.add(value.line()));
    for (var line : lines) {
      String key = "sale:line:" + line.id();
      if (held.contains(line.id()) || keys.contains(key)) continue;
      ledger.append(new Entry(key, account.merchantId(), order.id(), line.id(), "MERCHANDISE",
        line.merchandise(), ZERO, fee(line.merchandise(), account), order.completedAt(), null));
      net.put(line.id(), line.merchandise());
    }
    String freightKey = "sale:freight:" + order.id();
    if (held.isEmpty() && !keys.contains(freightKey) && order.freight().signum() > 0) {
      ledger.append(new Entry(freightKey, account.merchantId(), order.id(), null, "FREIGHT",
        ZERO, order.freight(), ZERO, order.completedAt(), null));
      keys.add(freightKey);
    }
    for (var refund : refunds) {
      var evidence = refund.evidence();
      String key = "refund:" + evidence.id();
      BigDecimal before = net.get(evidence.line());
      if (keys.contains(key) || before == null || held.contains(evidence.line())
        || (evidence.freight().signum() > 0 && !keys.contains(freightKey))) continue;
      BigDecimal after = before.subtract(evidence.merchandise());
      if (after.signum() < 0) throw new IllegalStateException("Refund exceeds original merchandise");
      ledger.append(new Entry(key, account.merchantId(), order.id(), evidence.line(), "REFUND",
        evidence.merchandise().negate(), evidence.freight().negate(),
        fee(after, account).subtract(fee(before, account)), refund.at(), null));
      net.put(evidence.line(), after);
    }
    return held;
  }

  private List<RefundFact> successfulRefunds(SettlementOrderV1.Order order, List<SettlementRefundV1.Refund> cases,
                                            Instant cutoff) {
    var output = new ArrayList<RefundFact>();
    for (var evidence : cases) {
      if (!"COMPLETED".equals(evidence.status())) continue;
      BigDecimal amount = evidence.merchandise().add(evidence.freight());
      if (amount.signum() == 0) continue;
      var channel = payments.refund(new RefundQueryV1(RefundQueryV1.VERSION, evidence.number())).orElse(null);
      if (channel == null || !order.id().equals(channel.orderId()) || !order.currency().equals(channel.currency())
        || !"SUCCEEDED".equals(channel.status()) || channel.succeededAt() == null
        || amount.compareTo(channel.amount()) != 0) {
        throw new IllegalStateException("After-sales refund does not reconcile");
      }
      if (!channel.succeededAt().isAfter(cutoff)) output.add(new RefundFact(evidence, channel.succeededAt()));
    }
    output.sort(Comparator.comparing(RefundFact::at).thenComparing(value -> value.evidence().id()));
    return output;
  }

  private BigDecimal fee(BigDecimal merchandise, Account account) {
    return merchandise.multiply(BigDecimal.valueOf(account.commissionBps(), 4)).setScale(2, RoundingMode.HALF_UP);
  }

  private record RefundFact(SettlementRefundV1.Refund evidence, Instant at) {
  }
}
