package vip.fairy.commerceflow.commerce.merchant.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Append-only operator conclusion and the statement discrepancy observed at submission.
 *
 * @param requestId idempotency key for the original review submission
 * @param statementId frozen statement being reviewed
 * @param merchantId store that owns the frozen statement
 * @param orderId order for an order-level discrepancy, otherwise null
 * @param orderLineId order line for a quantity discrepancy, otherwise null
 * @param differenceKind discrepancy code used by the reconciliation report
 * @param evidenceReference statement, ledger entry or receipt reference from the report
 * @param expectedValue expected amount or quantity reported at submission, possibly absent
 * @param observedValue observed amount or quantity reported at submission, possibly absent
 * @param decision confirmed discrepancy or a conclusion that no action is required
 * @param reason review rationale entered by the administrator
 * @param reviewedBy current administrator's account identifier
 * @param reviewedAt database timestamp assigned to the append
 */
public record StatementDifferenceReview(String requestId, String statementId, long merchantId, Long orderId,
                                        Long orderLineId, String differenceKind, String evidenceReference,
                                        BigDecimal expectedValue, BigDecimal observedValue, Decision decision,
                                        String reason, long reviewedBy, Instant reviewedAt) {

  public enum Decision {
    CONFIRMED, NO_ACTION
  }

  public Target target() {
    return new Target(orderId, orderLineId, differenceKind, evidenceReference, expectedValue, observedValue);
  }

  public record Target(Long orderId, Long orderLineId, String kind, String reference, BigDecimal expected,
                       BigDecimal observed) {

    public boolean sameEvidence(Target other) {
      return Objects.equals(orderId, other.orderId) && Objects.equals(orderLineId, other.orderLineId)
        && Objects.equals(kind, other.kind) && Objects.equals(reference, other.reference)
        && sameValue(expected, other.expected) && sameValue(observed, other.observed);
    }

    private static boolean sameValue(BigDecimal left, BigDecimal right) {
      return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
  }
}
