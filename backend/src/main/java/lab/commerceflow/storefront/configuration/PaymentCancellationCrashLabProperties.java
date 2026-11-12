package lab.commerceflow.storefront.configuration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.payment-cancellation-crash-lab")
public class PaymentCancellationCrashLabProperties {
  public enum Phase {
    AFTER_CANCELLATION_BEGIN,
    AFTER_CHANNEL_CLOSE,
    AFTER_PAYMENT_RECORD
  }

  private boolean enabled;
  @Min(1) private long orderId = 1;
  @NotNull private Phase phase = Phase.AFTER_CANCELLATION_BEGIN;
  @NotNull private Duration pause = Duration.ofMinutes(5);

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public long getOrderId() { return orderId; }
  public void setOrderId(long orderId) { this.orderId = orderId; }
  public Phase getPhase() { return phase; }
  public void setPhase(Phase phase) { this.phase = phase; }
  public Duration getPause() { return pause; }
  public void setPause(Duration pause) { this.pause = pause; }
}
