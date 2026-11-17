package vip.fairy.commerceflow.commerce.ordering.domain;

public class OrderResultInboxView {

  /** Stable result identity used for Inbox deduplication. */
  private String resultId;
  /** SHA-256 fingerprint used to reject result ID payload conflicts. */
  private String payloadFingerprint;
  /** Inbox state: received, applied, ignored or conflict. */
  private String processingStatus;

  public String getResultId() {
    return resultId;
  }

  public String getPayloadFingerprint() {
    return payloadFingerprint;
  }

  public String getProcessingStatus() {
    return processingStatus;
  }
}
