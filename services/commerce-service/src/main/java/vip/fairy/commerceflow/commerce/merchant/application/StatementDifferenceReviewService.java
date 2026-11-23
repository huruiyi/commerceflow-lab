package vip.fairy.commerceflow.commerce.merchant.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.merchant.application.StatementReconciliationService.Difference;
import vip.fairy.commerceflow.commerce.merchant.application.StatementReconciliationService.Report;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceReview;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceReview.Decision;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceReview.Target;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.StatementDifferenceReviewMapper;
import vip.fairy.commerceflow.commerce.shared.api.AdminListPage;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@Service
public class StatementDifferenceReviewService {

  private final StatementReconciliationService reports;
  private final StatementDifferenceReviewMapper reviews;

  StatementDifferenceReviewService(StatementReconciliationService reports, StatementDifferenceReviewMapper reviews) {
    this.reports = reports;
    this.reviews = reviews;
  }

  @Transactional(readOnly = true)
  public AdminListPage<StatementDifferenceReview> list(long merchant, String statement, int requestedPage,
                                                       int requestedSize) {
    reports.requireStatement(merchant, statement);
    int size = AdminListPage.boundedSize(requestedSize);
    long total = reviews.count(merchant, statement);
    int page = AdminListPage.availablePage(requestedPage, total, size);
    return new AdminListPage<>(reviews.page(merchant, statement, size, (long) page * size), total, page, size);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public StatementDifferenceReview record(long merchant, String statement, long actor, Command command) {
    reports.requireStatement(merchant, statement);
    String requestId = command.requestId().toString();
    String reason = command.reason().strip();
    StatementDifferenceReview previous = reviews.find(requestId);
    if (previous != null) return replay(previous, merchant, statement, actor, command, reason);

    Report report = reports.reconcile(merchant, statement, command.page(), command.size());
    Target evidence = targets(report).stream()
      .filter(command.target()::sameEvidence)
      .findFirst()
      .orElseThrow(() ->
        new BusinessRuleException("Difference changed or is no longer present. Reload reconciliation."));
    var review = new StatementDifferenceReview(requestId, statement, merchant, evidence.orderId(),
      evidence.orderLineId(), evidence.kind(), evidence.reference(), evidence.expected(), evidence.observed(),
      command.decision(), reason, actor, null);
    try {
      reviews.insert(review);
    } catch (DuplicateKeyException conflict) {
      return replay(reviews.find(requestId), merchant, statement, actor, command, reason);
    }
    return reviews.find(requestId);
  }

  private static StatementDifferenceReview replay(StatementDifferenceReview previous, long merchant, String statement,
                                                  long actor, Command command, String reason) {
    if (previous == null || previous.merchantId() != merchant || !previous.statementId().equals(statement)
      || previous.reviewedBy() != actor || previous.decision() != command.decision()
      || !previous.reason().equals(reason) || !previous.target().sameEvidence(command.target())) {
      throw new BusinessRuleException("Review request conflicts with the original submission");
    }
    return previous;
  }

  private static List<Target> targets(Report report) {
    List<Target> targets = new ArrayList<>();
    report.differences().forEach(difference -> targets.add(financialTarget(null, difference)));
    for (var order : report.orders()) {
      order.differences().forEach(difference -> targets.add(financialTarget(order.orderId(), difference)));
      if (order.fulfillment() == null) continue;
      for (var difference : order.fulfillment().differences()) {
        targets.add(new Target(order.orderId(), difference.orderLineId(), difference.kind(), difference.outboundId(),
          quantity(difference.expected()), quantity(difference.observed())));
      }
    }
    return targets;
  }

  private static Target financialTarget(Long orderId, Difference difference) {
    return new Target(orderId, null, difference.kind(), difference.reference(),
      difference.expected(), difference.observed());
  }

  private static BigDecimal quantity(Integer value) {
    return value == null ? null : BigDecimal.valueOf(value);
  }

  public record Command(UUID requestId, int page, int size, Target target, Decision decision, String reason) {
  }
}
