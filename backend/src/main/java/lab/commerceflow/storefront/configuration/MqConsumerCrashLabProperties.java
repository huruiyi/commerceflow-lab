package lab.commerceflow.storefront.configuration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.scheduling.mq-crash-lab")
public class MqConsumerCrashLabProperties {
  private boolean enabled;
  @Min(1) private long taskId = 1;
  @NotNull private Duration pause = Duration.ofMinutes(5);

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public long getTaskId() { return taskId; }
  public void setTaskId(long taskId) { this.taskId = taskId; }
  public Duration getPause() { return pause; }
  public void setPause(Duration pause) { this.pause = pause; }
}
