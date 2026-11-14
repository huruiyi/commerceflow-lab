package vip.fairy.commerceflow.commerce.notification.infrastructure.projection;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.notification.projection")
public class NotificationProjectionProperties {

  private boolean enabled;
  @Min(1)
  private int minimumMatchedEvents = 5;
  @Min(1)
  private int requiredConsecutivePassingChecks = 3;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getMinimumMatchedEvents() {
    return minimumMatchedEvents;
  }

  public void setMinimumMatchedEvents(int minimumMatchedEvents) {
    this.minimumMatchedEvents = minimumMatchedEvents;
  }

  public int getRequiredConsecutivePassingChecks() {
    return requiredConsecutivePassingChecks;
  }

  public void setRequiredConsecutivePassingChecks(int requiredConsecutivePassingChecks) {
    this.requiredConsecutivePassingChecks = requiredConsecutivePassingChecks;
  }
}
