package vip.fairy.commerceflow.commerce.notification.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.notification.delivery")
public class NotificationDeliveryProperties {

  @NotNull
  private Mode mode = Mode.IN_PROCESS;
  @NotBlank
  private String topic = "customer-notification-commands";
  @Min(1)
  private int maxAttempts = 5;
  @NotNull
  private Duration retryDelay = Duration.ofSeconds(5);
  private boolean fallbackToInProcess;

  public Mode getMode() {
    return mode;
  }

  public void setMode(Mode mode) {
    this.mode = mode;
  }

  public String getTopic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
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

  public boolean isFallbackToInProcess() {
    return fallbackToInProcess;
  }

  public void setFallbackToInProcess(boolean fallbackToInProcess) {
    this.fallbackToInProcess = fallbackToInProcess;
  }

  public enum Mode {
    IN_PROCESS,
    KAFKA
  }
}
