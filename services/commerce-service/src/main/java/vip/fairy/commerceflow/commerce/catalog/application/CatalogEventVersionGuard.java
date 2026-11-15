package vip.fairy.commerceflow.commerce.catalog.application;

public final class CatalogEventVersionGuard {

  private CatalogEventVersionGuard() {
  }

  public static Decision decide(long lastAppliedVersion, CatalogProductEventV1 event) {
    event.validate();
    if (event.businessVersion() <= lastAppliedVersion) {
      return Decision.STALE;
    }
    if (event.businessVersion() == lastAppliedVersion + 1) {
      return Decision.APPLY;
    }
    return Decision.RETRY_VERSION_GAP;
  }

  public enum Decision {
    APPLY,
    STALE,
    RETRY_VERSION_GAP
  }
}
