package vip.fairy.commerceflow.commerce.merchant.application;

import java.time.Instant;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.StocktakeDispositionV1;
import vip.fairy.commerceflow.commerce.invoice.application.contract.v1.SettlementInvoiceV1;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceDisposition;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceDisposition.Route;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceDisposition.View;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceReview;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceReview.Decision;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.MerchantPayoutMapper;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.StatementDifferenceDispositionMapper;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.StatementDifferenceReviewMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class StatementDifferenceDispositionService {

  private final StatementDifferenceReviewMapper reviews;
  private final StatementDifferenceDispositionMapper dispositions;
  private final StatementReconciliationService reports;
  private final MerchantPayoutMapper payouts;
  private final StocktakeDispositionV1 stocktakes;
  private final SettlementInvoiceV1 invoices;

  StatementDifferenceDispositionService(StatementDifferenceReviewMapper reviews,
                                         StatementDifferenceDispositionMapper dispositions,
                                         StatementReconciliationService reports, MerchantPayoutMapper payouts,
                                         StocktakeDispositionV1 stocktakes, SettlementInvoiceV1 invoices) {
    this.reviews = reviews;
    this.dispositions = dispositions;
    this.reports = reports;
    this.payouts = payouts;
    this.stocktakes = stocktakes;
    this.invoices = invoices;
  }

  @Transactional(readOnly = true)
  public View get(long merchant, String statement, String reviewId) {
    return view(require(merchant, statement, reviewId), dispositions.find(reviewId));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public View link(long merchant, String statement, String reviewId, long actor, String requestId, String receipt) {
    var review = require(merchant, statement, reviewId);
    if (review.decision() != Decision.CONFIRMED) throw new BusinessRuleException("This review needs no correction");
    var existing = dispositions.find(reviewId);
    if (existing != null) return linkedReplay(review, existing, actor, requestId, receipt);
    if (!validReceipt(review, receipt)) {
      throw new BusinessRuleException("Receipt does not match the reviewed difference");
    }
    try {
      dispositions.insert(new StatementDifferenceDisposition(reviewId, requestId, receipt, actor, Instant.now(),
        null, null, null, null));
    } catch (DuplicateKeyException conflict) {
      return linkedReplay(review, dispositions.find(reviewId), actor, requestId, receipt);
    }
    return view(review, dispositions.find(reviewId));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public View close(long merchant, String statement, String reviewId, long actor, String requestId, String reason) {
    var review = require(merchant, statement, reviewId);
    var existing = dispositions.find(reviewId);
    if (existing != null && existing.closedAt() != null) {
      return closureReplay(review, existing, actor, requestId, reason);
    }
    if (review.decision() == Decision.CONFIRMED && (existing == null || existing.receipt() == null)) {
      throw new BusinessRuleException("Link the original correction receipt before closing");
    }
    boolean corrected = reports.corrected(review);
    if (review.decision() == Decision.CONFIRMED && !corrected) {
      throw new BusinessRuleException("Reconciliation still reports the difference");
    }
    if (review.decision() == Decision.NO_ACTION) {
      try {
        dispositions.insert(new StatementDifferenceDisposition(reviewId, null, null, null, null, requestId,
          actor, Instant.now(), reason));
      } catch (DuplicateKeyException conflict) {
        return closureReplay(review, dispositions.find(reviewId), actor, requestId, reason);
      }
    } else if (dispositions.close(reviewId, requestId, actor, reason) == 0) {
      return closureReplay(review, dispositions.find(reviewId), actor, requestId, reason);
    }
    return view(review, dispositions.find(reviewId));
  }

  private boolean validReceipt(StatementDifferenceReview review, String receipt) {
    return switch (Route.forKind(review.differenceKind())) {
      case STOCKTAKE -> review.orderId() != null
        && reports.stockProduct(review).stream()
          .anyMatch(product -> stocktakes.applied(receipt, review.orderId(), product, review.reviewedAt()));
      case PAYOUT -> payoutMatches(review, receipt);
      case INVOICE -> review.orderId() != null
        && invoices.reviewed(review.merchantId(), review.orderId(), receipt, review.reviewedAt());
      case MANUAL -> false;
    };
  }

  private boolean payoutMatches(StatementDifferenceReview review, String receipt) {
    var attempt = payouts.find(receipt);
    return attempt != null && "SUCCEEDED".equals(attempt.status()) && attempt.recoveryOf() != null
      && Objects.equals(attempt.merchantId(), review.merchantId()) && review.statementId().equals(attempt.statementId())
      && attempt.resolvedAt() != null && !attempt.resolvedAt().isBefore(review.reviewedAt())
      && (!"PAYOUT_FAILED".equals(review.differenceKind()) || attempt.recoveryOf().equals(review.evidenceReference()));
  }

  private StatementDifferenceReview require(long merchant, String statement, String reviewId) {
    reports.requireStatement(merchant, statement);
    var review = reviews.find(reviewId);
    if (review == null || review.merchantId() != merchant || !statement.equals(review.statementId())) {
      throw new ResourceNotFoundException("Statement difference review not found");
    }
    return review;
  }

  private static View linkedReplay(StatementDifferenceReview review, StatementDifferenceDisposition record,
                                   long actor, String requestId, String receipt) {
    if (record == null || !requestId.equals(record.receiptRequestId()) || !receipt.equals(record.receipt())
      || !Objects.equals(actor, record.linkedBy())) {
      throw new BusinessRuleException("A different correction receipt is already linked");
    }
    return view(review, record);
  }

  private static View closureReplay(StatementDifferenceReview review, StatementDifferenceDisposition record,
                                    long actor, String requestId, String reason) {
    if (record == null || !requestId.equals(record.closureRequestId()) || !reason.equals(record.closureReason())
      || !Objects.equals(actor, record.closedBy())) {
      throw new BusinessRuleException("This review was closed by a different request");
    }
    return view(review, record);
  }

  private static View view(StatementDifferenceReview review, StatementDifferenceDisposition record) {
    String status = record == null ? "OPEN" : record.closedAt() == null ? "EVIDENCE_LINKED" : "CLOSED";
    return new View(Route.forKind(review.differenceKind()), status, record);
  }
}
