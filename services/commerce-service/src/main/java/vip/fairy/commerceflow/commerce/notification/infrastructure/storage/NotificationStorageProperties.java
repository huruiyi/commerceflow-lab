package vip.fairy.commerceflow.commerce.notification.infrastructure.storage;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.notification.storage")
public class NotificationStorageProperties {

  public enum Mode {
    COMMERCE,
    SHADOW,
    INDEPENDENT
  }

  private boolean enabled;
  private Mode mode = Mode.COMMERCE;
  private String url = "jdbc:mysql://localhost:3306/notification";
  private String username = "notification";
  private String password = "notification-password";
  private String driverClassName = "com.mysql.cj.jdbc.Driver";
  @Min(1)
  private int backfillBatchSize = 100;
  @Min(1)
  private int verificationBatchSize = 200;
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

  public Mode getMode() {
    return mode;
  }

  public void setMode(Mode mode) {
    this.mode = mode;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getDriverClassName() {
    return driverClassName;
  }

  public void setDriverClassName(String driverClassName) {
    this.driverClassName = driverClassName;
  }

  public int getBackfillBatchSize() {
    return backfillBatchSize;
  }

  public void setBackfillBatchSize(int backfillBatchSize) {
    this.backfillBatchSize = backfillBatchSize;
  }

  public int getVerificationBatchSize() {
    return verificationBatchSize;
  }

  public void setVerificationBatchSize(int verificationBatchSize) {
    this.verificationBatchSize = verificationBatchSize;
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

  public boolean usesIndependentReadModel() {
    return enabled && mode == Mode.INDEPENDENT;
  }
}
