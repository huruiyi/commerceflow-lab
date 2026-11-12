package lab.commerceflow.storefront.configuration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.scheduling.rocketmq")
public class RocketMqTaskTriggerProperties {
  private boolean enabled;
  @NotBlank private String endpoints = "localhost:8081";
  @NotBlank private String topic = "commerceflow-scheduled-task";
  @NotBlank private String consumerGroup = "commerceflow-scheduled-task-consumer";
  @NotBlank private String tag = "SCHEDULED_TASK";
  @NotNull private Duration requestTimeout = Duration.ofSeconds(5);
  @NotNull private Duration publishInitialDelay = Duration.ofSeconds(2);
  @NotNull private Duration publishFixedDelay = Duration.ofSeconds(1);
  @Min(1) @Max(10000) private int publishBatchSize = 100;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getEndpoints() { return endpoints; }
  public void setEndpoints(String endpoints) { this.endpoints = endpoints; }
  public String getTopic() { return topic; }
  public void setTopic(String topic) { this.topic = topic; }
  public String getConsumerGroup() { return consumerGroup; }
  public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
  public String getTag() { return tag; }
  public void setTag(String tag) { this.tag = tag; }
  public Duration getRequestTimeout() { return requestTimeout; }
  public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
  public Duration getPublishInitialDelay() { return publishInitialDelay; }
  public void setPublishInitialDelay(Duration publishInitialDelay) { this.publishInitialDelay = publishInitialDelay; }
  public Duration getPublishFixedDelay() { return publishFixedDelay; }
  public void setPublishFixedDelay(Duration publishFixedDelay) { this.publishFixedDelay = publishFixedDelay; }
  public int getPublishBatchSize() { return publishBatchSize; }
  public void setPublishBatchSize(int publishBatchSize) { this.publishBatchSize = publishBatchSize; }
}
