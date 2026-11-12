package vip.fairy.commerceflow.commerce.scheduling.application;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;

import vip.fairy.commerceflow.commerce.scheduling.infrastructure.RocketMqTaskTriggerProperties;
import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskView;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "commerceflow.scheduling.rocketmq", name = "enabled",
  havingValue = "true")
public class RocketMqTaskTriggerPublisher {
  private final Producer producer;
  private final ClientServiceProvider provider;
  private final ObjectMapper mapper;
  private final RocketMqTaskTriggerProperties properties;
  private final Counter published;

  public RocketMqTaskTriggerPublisher(Producer producer, ClientServiceProvider provider,
                                      ObjectMapper mapper, RocketMqTaskTriggerProperties properties, MeterRegistry registry) {
    this.producer = producer;
    this.provider = provider;
    this.mapper = mapper;
    this.properties = properties;
    this.published = registry.counter("commerceflow.scheduled.tasks.rocketmq.published");
  }

  public SendReceipt publish(ScheduledTaskView task) throws Exception {
    RocketMqTaskTriggerMessage payload = new RocketMqTaskTriggerMessage(task.getId(),
      task.getTaskType(), task.getDueAt(), task.getPrimaryTriggerSnapshot());
    Message message = provider.newMessageBuilder()
      .setTopic(properties.getTopic())
      .setTag(properties.getTag())
      .setKeys("scheduled-task-" + task.getId())
      .setDeliveryTimestamp(task.getDueAt().toEpochMilli())
      .setBody(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8))
      .build();
    SendReceipt receipt = producer.send(message);
    published.increment();
    return receipt;
  }
}
