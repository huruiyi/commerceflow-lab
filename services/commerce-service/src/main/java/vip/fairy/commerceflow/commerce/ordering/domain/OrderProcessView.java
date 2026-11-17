package vip.fairy.commerceflow.commerce.ordering.domain;

import java.time.Instant;

public class OrderProcessView {

  /** Stable process identity, equal to the initiating command identity in V1. */
  private String processId;
  /** Stable order identity managed by this process. */
  private Long orderId;
  /** Business process type derived from the initiating command. */
  private String processType;
  /** Persistent process status, including recovery-required states. */
  private String status;
  /** Current durable process step. */
  private String currentStep;
  /** Result type that must arrive before the process can advance. */
  private String waitingFor;
  /** Idempotent outbound command identity. */
  private String commandId;
  /** SHA-256 fingerprint used to reject command ID payload conflicts. */
  private String commandFingerprint;
  /** End-to-end business correlation identity. */
  private String correlationId;
  /** Identity of the direct cause that started this process. */
  private String causationId;
  /** W3C trace context propagated across process boundaries. */
  private String traceparent;
  /** Number of recovery or delivery attempts. */
  private int attempts;
  /** Deadline after which a missing result becomes unknown and requires reconciliation. */
  private Instant resultDeadlineAt;
  /** Earliest instant at which recovery scanning may revisit the process. */
  private Instant nextCheckAt;
  /** Last diagnostic error; never used as a business decision key. */
  private String lastError;

  public String getProcessId() {
    return processId;
  }

  public Long getOrderId() {
    return orderId;
  }

  public String getProcessType() {
    return processType;
  }

  public String getStatus() {
    return status;
  }

  public String getCurrentStep() {
    return currentStep;
  }

  public String getWaitingFor() {
    return waitingFor;
  }

  public String getCommandId() {
    return commandId;
  }

  public String getCommandFingerprint() {
    return commandFingerprint;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public String getCausationId() {
    return causationId;
  }

  public String getTraceparent() {
    return traceparent;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getResultDeadlineAt() {
    return resultDeadlineAt;
  }

  public Instant getNextCheckAt() {
    return nextCheckAt;
  }

  public String getLastError() {
    return lastError;
  }
}
