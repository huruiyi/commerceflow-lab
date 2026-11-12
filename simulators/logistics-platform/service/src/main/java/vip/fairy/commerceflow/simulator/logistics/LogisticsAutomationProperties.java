package vip.fairy.commerceflow.simulator.logistics;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "logistics-platform.automation")
public class LogisticsAutomationProperties {
  private boolean enabled;
  @NotNull
  private Duration stepInterval = Duration.ofMinutes(5);
  @Min(1)
  @Max(1000)
  private int batchSize = 100;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getStepInterval() {
    return stepInterval;
  }

  public void setStepInterval(Duration stepInterval) {
    this.stepInterval = stepInterval;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  @AssertTrue(message = "automation step interval must be positive")
  public boolean isStepIntervalValid() {
    return stepInterval != null && !stepInterval.isZero() && !stepInterval.isNegative();
  }
}
