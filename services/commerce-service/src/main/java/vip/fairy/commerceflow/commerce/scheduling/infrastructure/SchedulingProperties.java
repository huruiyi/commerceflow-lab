package vip.fairy.commerceflow.commerce.scheduling.infrastructure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.scheduling")
public class SchedulingProperties {

  private boolean enabled = true;
  @Valid
  private Policy defaultPolicy = new Policy();
  @Valid
  private Map<String, Policy> taskPolicies = new LinkedHashMap<>();
  @Valid
  private Scan databaseScan = new Scan();
  @Valid
  private Scan reconciliation = new Scan();
  @Valid
  private Scan leaseRecovery = new Scan();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Policy getDefaultPolicy() {
    return defaultPolicy;
  }

  public void setDefaultPolicy(Policy defaultPolicy) {
    this.defaultPolicy = defaultPolicy;
  }

  public Map<String, Policy> getTaskPolicies() {
    return taskPolicies;
  }

  public void setTaskPolicies(Map<String, Policy> taskPolicies) {
    this.taskPolicies = taskPolicies;
  }

  public Scan getDatabaseScan() {
    return databaseScan;
  }

  public void setDatabaseScan(Scan databaseScan) {
    this.databaseScan = databaseScan;
  }

  public Scan getReconciliation() {
    return reconciliation;
  }

  public void setReconciliation(Scan reconciliation) {
    this.reconciliation = reconciliation;
  }

  public Scan getLeaseRecovery() {
    return leaseRecovery;
  }

  public void setLeaseRecovery(Scan leaseRecovery) {
    this.leaseRecovery = leaseRecovery;
  }

  public static class Policy {

    private boolean enabled = false;
    @NotBlank
    private String primaryTrigger = "DATABASE";
    private boolean reconciliationEnabled = true;
    @NotNull
    private Duration gracePeriod = Duration.ofMinutes(2);
    @NotNull
    private Duration claimLease = Duration.ofMinutes(2);
    @Min(1)
    @Max(100)
    private int maxAttempts = 5;
    @NotNull
    private Duration retryDelay = Duration.ofSeconds(30);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getPrimaryTrigger() {
      return primaryTrigger;
    }

    public void setPrimaryTrigger(String primaryTrigger) {
      this.primaryTrigger = primaryTrigger;
    }

    public boolean isReconciliationEnabled() {
      return reconciliationEnabled;
    }

    public void setReconciliationEnabled(boolean reconciliationEnabled) {
      this.reconciliationEnabled = reconciliationEnabled;
    }

    public Duration getGracePeriod() {
      return gracePeriod;
    }

    public void setGracePeriod(Duration gracePeriod) {
      this.gracePeriod = gracePeriod;
    }

    public Duration getClaimLease() {
      return claimLease;
    }

    public void setClaimLease(Duration claimLease) {
      this.claimLease = claimLease;
    }

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    public Duration getRetryDelay() {
      return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
      this.retryDelay = retryDelay;
    }

    @AssertTrue(message = "claim lease must be positive")
    public boolean isClaimLeaseValid() {
      return claimLease != null && !claimLease.isZero() && !claimLease.isNegative();
    }

    @AssertTrue(message = "grace period and retry delay must be non-negative")
    public boolean isNonNegativeDurationsValid() {
      return gracePeriod != null && !gracePeriod.isNegative()
        && retryDelay != null && !retryDelay.isNegative();
    }
  }

  public static class Scan {

    @NotNull
    private Duration initialDelay = Duration.ofSeconds(10);
    @NotNull
    private Duration fixedDelay = Duration.ofSeconds(15);
    @Min(1)
    @Max(10000)
    private int batchSize = 100;

    public Duration getInitialDelay() {
      return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
      this.initialDelay = initialDelay;
    }

    public Duration getFixedDelay() {
      return fixedDelay;
    }

    public void setFixedDelay(Duration fixedDelay) {
      this.fixedDelay = fixedDelay;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    @AssertTrue(message = "scan delays must be valid")
    public boolean isDelayValid() {
      return initialDelay != null && !initialDelay.isNegative()
        && fixedDelay != null && !fixedDelay.isZero() && !fixedDelay.isNegative();
    }
  }
}
