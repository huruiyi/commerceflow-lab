package vip.fairy.commerceflow.commerce.scheduling.application;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskView;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.ScheduledTaskMapper;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "commerceflow.scheduling.rocketmq", name = "enabled",
  havingValue = "true")
public class RocketMqTaskTriggerConsumer {
  private final ObjectMapper objectMapper;
  private final ScheduledTaskMapper mapper;
  private final ScheduledTaskExecutor executor;
  private final MqConsumerCrashLab crashLab;
  private final Counter consumed;
  private final Counter ignored;
  private final Counter malformed;

  public RocketMqTaskTriggerConsumer(
    ObjectMapper objectMapper,
    ScheduledTaskMapper mapper,
    ScheduledTaskExecutor executor,
    MqConsumerCrashLab crashLab,
    MeterRegistry registry
  ) {
    this.objectMapper = objectMapper;
    this.mapper = mapper;
    this.executor = executor;
    this.crashLab = crashLab;
    this.consumed = registry.counter("commerceflow.scheduled.tasks.rocketmq.consumed");
    this.ignored = registry.counter("commerceflow.scheduled.tasks.rocketmq.ignored");
    this.malformed = registry.counter("commerceflow.scheduled.tasks.rocketmq.malformed");
  }

  public ConsumeResult consume(MessageView raw) {
    try {
      ByteBuffer buffer = raw.getBody().duplicate();
      byte[] body = new byte[buffer.remaining()];
      buffer.get(body);
      RocketMqTaskTriggerMessage message = objectMapper.readValue(
        new String(body, StandardCharsets.UTF_8), RocketMqTaskTriggerMessage.class);
      if (message.taskId() == null || !TriggerStrategy.ROCKETMQ.name().equals(message.strategySnapshot())) {
        throw new IllegalArgumentException("Invalid RocketMQ task trigger payload");
      }
      ScheduledTaskView task = mapper.byId(message.taskId()).orElse(null);
      if (task == null || !TriggerStrategy.ROCKETMQ.name().equals(task.getPrimaryTriggerSnapshot())) {
        ignored.increment();
        return ConsumeResult.SUCCESS;
      }
      if (task.getDueAt().isAfter(Instant.now())) {
        mapper.clearTriggerPublished(task.getId(), TriggerStrategy.ROCKETMQ.name(),
          "RocketMQ delivered before current database due time; republishing from database truth");
        ignored.increment();
        return ConsumeResult.SUCCESS;
      }
      consumed.increment();
      crashLab.pauseAfterDelivery(task, TaskTriggerSource.ROCKETMQ);
      if (!executor.execute(task.getId(), TaskTriggerSource.ROCKETMQ)) ignored.increment();
      return ConsumeResult.SUCCESS;
    } catch (IllegalArgumentException exception) {
      malformed.increment();
      return ConsumeResult.FAILURE;
    } catch (Exception exception) {
      malformed.increment();
      return ConsumeResult.FAILURE;
    }
  }
}
