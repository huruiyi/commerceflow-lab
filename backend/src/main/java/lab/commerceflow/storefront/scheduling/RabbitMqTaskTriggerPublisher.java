package lab.commerceflow.storefront.scheduling;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import lab.commerceflow.storefront.configuration.RabbitMqTaskTriggerProperties;
import lab.commerceflow.storefront.domain.ScheduledTaskView;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "commerceflow.scheduling.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMqTaskTriggerPublisher {
  private final RabbitTemplate rabbit;
  private final ObjectMapper mapper;
  private final RabbitMqTaskTriggerProperties properties;
  private final Counter published;

  public RabbitMqTaskTriggerPublisher(RabbitTemplate rabbit, ObjectMapper mapper,
      RabbitMqTaskTriggerProperties properties, MeterRegistry registry) {
    this.rabbit = rabbit; this.mapper = mapper; this.properties = properties;
    this.rabbit.setMandatory(true);
    this.published = registry.counter("commerceflow.scheduled.tasks.rabbitmq.published");
  }

  public void publish(ScheduledTaskView task) throws Exception {
    RabbitMqTaskTriggerMessage payload = new RabbitMqTaskTriggerMessage(task.getId(),
        task.getTaskType(), task.getDueAt(), task.getPrimaryTriggerSnapshot());
    byte[] body = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    Message message = MessageBuilder.withBody(body).setContentType("application/json")
        .setContentEncoding(StandardCharsets.UTF_8.name()).setMessageId("scheduled-task-" + task.getId())
        .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
    CorrelationData correlation = new CorrelationData("scheduled-task-" + task.getId() + "-" + task.getVersion());
    rabbit.send(properties.getDelayExchange(), bucketRoutingKey(task.getDueAt()), message, correlation);
    CorrelationData.Confirm confirm = correlation.getFuture().get(
        properties.getPublisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
    if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ publisher NACK: " + confirm.getReason());
    if (correlation.getReturned() != null) throw new IllegalStateException("RabbitMQ message was returned: " + correlation.getReturned().getReplyText());
    published.increment();
  }

  private String bucketRoutingKey(Instant dueAt) {
    long remaining = Math.max(1, Duration.between(Instant.now(), dueAt).toMillis());
    long bucket = properties.getDelayBuckets().stream().map(Duration::toMillis)
        .filter(value -> value <= remaining).max(Comparator.naturalOrder())
        .orElseGet(() -> properties.getDelayBuckets().stream().map(Duration::toMillis).min(Long::compare).orElse(1000L));
    return "bucket." + bucket;
  }
}
