package vip.fairy.commerceflow.commerce.operations.domain;

import java.time.Instant;

/** Read model for an outstanding or resolved business exception. */
public record ExceptionWork(Kind kind, String sourceId, long merchantId, String statementId, Long orderId,
                            String reasonCode, Priority priority, State state, Instant updatedAt) {

  public enum Kind { STATEMENT_DIFFERENCE, RETURN_WORK }

  public enum Priority { HIGH, NORMAL }

  public enum State { OPEN, WAITING_EVIDENCE, CLOSED }

  public record Filter(Kind kind, Priority priority, State state, Long orderId) {
  }
}
