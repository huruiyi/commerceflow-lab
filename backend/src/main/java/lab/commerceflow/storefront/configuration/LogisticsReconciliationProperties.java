package lab.commerceflow.storefront.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.logistics-platform.reconciliation")
public class LogisticsReconciliationProperties {
  private boolean enabled = true;
  @NotNull private Duration initialDelay = Duration.ofSeconds(30);
  @NotNull private Duration scanInterval = Duration.ofMinutes(1);
  @NotNull private Duration staleAfter = Duration.ofMinutes(5);
  @Min(1) @Max(1000) private int batchSize = 100;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public Duration getInitialDelay() { return initialDelay; }
  public void setInitialDelay(Duration initialDelay) { this.initialDelay = initialDelay; }
  public Duration getScanInterval() { return scanInterval; }
  public void setScanInterval(Duration scanInterval) { this.scanInterval = scanInterval; }
  public Duration getStaleAfter() { return staleAfter; }
  public void setStaleAfter(Duration staleAfter) { this.staleAfter = staleAfter; }
  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

  @AssertTrue(message = "logistics reconciliation durations must be valid")
  public boolean isDurationConfigurationValid() {
    return initialDelay != null && !initialDelay.isNegative()
        && scanInterval != null && !scanInterval.isNegative() && !scanInterval.isZero()
        && staleAfter != null && !staleAfter.isNegative();
  }
}
