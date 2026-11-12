package lab.commerceflow.storefront.scheduling;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ScheduledTaskTypes {
  public static final String PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";
  public static final String AUTO_RECEIVE = "AUTO_RECEIVE";
  public static final String AUTO_COMPLETE = "AUTO_COMPLETE";
  public static final String REFUND_RECONCILIATION = "REFUND_RECONCILIATION";
  public static final String PAYMENT_RECONCILIATION = "PAYMENT_RECONCILIATION";
  public static final String LOGISTICS_RECONCILIATION = "LOGISTICS_RECONCILIATION";
  public static final String NOTIFICATION_RETRY = "NOTIFICATION_RETRY";
  public static final String REVIEW_REMINDER = "REVIEW_REMINDER";
  public static final String INVENTORY_RELEASE = "INVENTORY_RELEASE";
  public static final String AFTER_SALE_DEADLINE = "AFTER_SALE_DEADLINE";

  private static final Set<String> BUILT_IN = Set.of(
      PAYMENT_TIMEOUT, AUTO_RECEIVE, AUTO_COMPLETE, REFUND_RECONCILIATION,
      PAYMENT_RECONCILIATION, LOGISTICS_RECONCILIATION, NOTIFICATION_RETRY,
      REVIEW_REMINDER, INVENTORY_RELEASE, AFTER_SALE_DEADLINE);

  private ScheduledTaskTypes() {}

  public static Set<String> builtIn() {
    return new LinkedHashSet<>(BUILT_IN.stream().sorted().toList());
  }

  public static String normalize(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Task type is required");
    String normalized = value.trim().toUpperCase();
    if (!normalized.matches("[A-Z][A-Z0-9_]{2,63}")) {
      throw new IllegalArgumentException("Task type must match [A-Z][A-Z0-9_]{2,63}");
    }
    return normalized;
  }
}
