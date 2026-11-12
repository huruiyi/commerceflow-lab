package vip.fairy.commerceflow.commerce.scheduling.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;

import vip.fairy.commerceflow.commerce.scheduling.infrastructure.RabbitMqTaskTriggerProperties;
import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskView;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.ScheduledTaskMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "commerceflow.scheduling.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMqTaskTriggerDispatcher {
  private final ScheduledTaskMapper mapper;
  private final RabbitMqTaskTriggerPublisher publisher;
  private final RabbitMqTaskTriggerProperties properties;
  private final Counter failed;

  public RabbitMqTaskTriggerDispatcher(ScheduledTaskMapper mapper, RabbitMqTaskTriggerPublisher publisher,
                                       RabbitMqTaskTriggerProperties properties, MeterRegistry registry) {
    this.mapper = mapper;
    this.publisher = publisher;
    this.properties = properties;
    this.failed = registry.counter("commerceflow.scheduled.tasks.rabbitmq.publish.failed");
  }

  @Scheduled(initialDelayString = "${commerceflow.scheduling.rabbitmq.publish-initial-delay:2s}",
    fixedDelayString = "${commerceflow.scheduling.rabbitmq.publish-fixed-delay:1s}")
  @SchedulerLock(name = "rabbitMqScheduledTaskPublisher", lockAtLeastFor = "PT0.1S", lockAtMostFor = "PT1M")
  public void run() {
    for (Long id : mapper.triggerPublishCandidates(TriggerStrategy.RABBITMQ.name(),
      properties.getPublishBatchSize())) {
      ScheduledTaskView task = mapper.byId(id).orElse(null);
      if (task == null) continue;
      try {
        publisher.publish(task);
        if (mapper.markTriggerPublished(id, TriggerStrategy.RABBITMQ.name(), Instant.now()) == 1)
          mapper.insertEvent(id, "TASK_TRIGGER_PUBLISHED", TaskTriggerSource.RABBITMQ.name(), null, null, "RabbitMQ publisher confirm received");
      } catch (Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (message.length() > 1000) message = message.substring(0, 1000);
        mapper.markTriggerPublishFailure(id, message);
        failed.increment();
      }
    }
  }
}
