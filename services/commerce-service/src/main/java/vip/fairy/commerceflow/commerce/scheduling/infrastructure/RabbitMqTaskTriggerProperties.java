package vip.fairy.commerceflow.commerce.scheduling.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.scheduling.rabbitmq")
public class RabbitMqTaskTriggerProperties {

  private boolean enabled;
  @NotNull
  private Duration publishInitialDelay = Duration.ofSeconds(2);
  @NotNull
  private Duration publishFixedDelay = Duration.ofSeconds(1);
  @Min(1)
  @Max(10000)
  private int publishBatchSize = 100;
  @NotNull
  private Duration publisherConfirmTimeout = Duration.ofSeconds(5);
  @NotBlank
  private String delayExchange = "commerceflow.scheduled-task.delay";
  @NotBlank
  private String readyExchange = "commerceflow.scheduled-task.ready";
  @NotBlank
  private String deadExchange = "commerceflow.scheduled-task.dead";
  @NotBlank
  private String readyQueue = "commerceflow.scheduled-task.ready";
  @NotBlank
  private String deadQueue = "commerceflow.scheduled-task.dead";
  @NotBlank
  private String readyRoutingKey = "scheduled-task.ready";
  @NotBlank
  private String deadRoutingKey = "scheduled-task.dead";
  @NotEmpty
  private List<Duration> delayBuckets = new ArrayList<>(List.of(
    Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
    Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofDays(1)));

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    enabled = value;
  }

  public Duration getPublishInitialDelay() {
    return publishInitialDelay;
  }

  public void setPublishInitialDelay(Duration value) {
    publishInitialDelay = value;
  }

  public Duration getPublishFixedDelay() {
    return publishFixedDelay;
  }

  public void setPublishFixedDelay(Duration value) {
    publishFixedDelay = value;
  }

  public int getPublishBatchSize() {
    return publishBatchSize;
  }

  public void setPublishBatchSize(int value) {
    publishBatchSize = value;
  }

  public Duration getPublisherConfirmTimeout() {
    return publisherConfirmTimeout;
  }

  public void setPublisherConfirmTimeout(Duration value) {
    publisherConfirmTimeout = value;
  }

  public String getDelayExchange() {
    return delayExchange;
  }

  public void setDelayExchange(String value) {
    delayExchange = value;
  }

  public String getReadyExchange() {
    return readyExchange;
  }

  public void setReadyExchange(String value) {
    readyExchange = value;
  }

  public String getDeadExchange() {
    return deadExchange;
  }

  public void setDeadExchange(String value) {
    deadExchange = value;
  }

  public String getReadyQueue() {
    return readyQueue;
  }

  public void setReadyQueue(String value) {
    readyQueue = value;
  }

  public String getDeadQueue() {
    return deadQueue;
  }

  public void setDeadQueue(String value) {
    deadQueue = value;
  }

  public String getReadyRoutingKey() {
    return readyRoutingKey;
  }

  public void setReadyRoutingKey(String value) {
    readyRoutingKey = value;
  }

  public String getDeadRoutingKey() {
    return deadRoutingKey;
  }

  public void setDeadRoutingKey(String value) {
    deadRoutingKey = value;
  }

  public List<Duration> getDelayBuckets() {
    return delayBuckets;
  }

  public void setDelayBuckets(List<Duration> value) {
    delayBuckets = value;
  }
}
