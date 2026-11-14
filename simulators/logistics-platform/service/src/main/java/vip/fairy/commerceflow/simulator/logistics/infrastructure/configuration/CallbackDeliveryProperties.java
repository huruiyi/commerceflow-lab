package vip.fairy.commerceflow.simulator.logistics.infrastructure.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "logistics-platform.callback-delivery")
public class CallbackDeliveryProperties {

  private boolean enabled = true;
  @NotNull
  private Duration initialDelay = Duration.ofSeconds(2);
  @NotNull
  private Duration scanInterval = Duration.ofSeconds(1);
  @NotNull
  private Duration retryDelay = Duration.ofSeconds(2);
  @NotNull
  private Duration claimLease = Duration.ofSeconds(30);
  @NotNull
  private Duration connectTimeout = Duration.ofSeconds(2);
  @NotNull
  private Duration readTimeout = Duration.ofSeconds(3);
  @Min(1)
  @Max(1000)
  private int batchSize = 50;
  @Min(1)
  @Max(100)
  private int maxAttempts = 10;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getInitialDelay() {
    return initialDelay;
  }

  public void setInitialDelay(Duration initialDelay) {
    this.initialDelay = initialDelay;
  }

  public Duration getScanInterval() {
    return scanInterval;
  }

  public void setScanInterval(Duration scanInterval) {
    this.scanInterval = scanInterval;
  }

  public Duration getRetryDelay() {
    return retryDelay;
  }

  public void setRetryDelay(Duration retryDelay) {
    this.retryDelay = retryDelay;
  }

  public Duration getClaimLease() {
    return claimLease;
  }

  public void setClaimLease(Duration claimLease) {
    this.claimLease = claimLease;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  @AssertTrue(message = "callback delivery durations must be positive")
  public boolean isDurationConfigurationValid() {
    return positive(initialDelay, true) && positive(scanInterval, false)
      && positive(retryDelay, false) && positive(claimLease, false)
      && positive(connectTimeout, false) && positive(readTimeout, false);
  }

  private boolean positive(Duration value, boolean zeroAllowed) {
    return value != null && !value.isNegative() && (zeroAllowed || !value.isZero());
  }
}
