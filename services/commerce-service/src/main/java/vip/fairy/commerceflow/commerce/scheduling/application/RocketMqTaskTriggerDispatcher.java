package vip.fairy.commerceflow.commerce.scheduling.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;

import vip.fairy.commerceflow.commerce.scheduling.infrastructure.RocketMqTaskTriggerProperties;
import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskView;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.ScheduledTaskMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "commerceflow.scheduling.rocketmq", name = "enabled",
  havingValue = "true")
public class RocketMqTaskTriggerDispatcher {

  private final ScheduledTaskMapper mapper;
  private final RocketMqTaskTriggerPublisher publisher;
  private final RocketMqTaskTriggerProperties properties;
  private final Counter failed;

  public RocketMqTaskTriggerDispatcher(ScheduledTaskMapper mapper,
                                       RocketMqTaskTriggerPublisher publisher, RocketMqTaskTriggerProperties properties,
                                       MeterRegistry registry) {
    this.mapper = mapper;
    this.publisher = publisher;
    this.properties = properties;
    this.failed = registry.counter("commerceflow.scheduled.tasks.rocketmq.publish.failed");
  }

  @Scheduled(initialDelayString = "${commerceflow.scheduling.rocketmq.publish-initial-delay:2s}",
    fixedDelayString = "${commerceflow.scheduling.rocketmq.publish-fixed-delay:1s}")
  @SchedulerLock(name = "rocketMqScheduledTaskPublisher", lockAtLeastFor = "PT0.1S",
    lockAtMostFor = "PT1M")
  public void run() {
    for (Long id : mapper.triggerPublishCandidates(TriggerStrategy.ROCKETMQ.name(),
      properties.getPublishBatchSize())) {
      ScheduledTaskView task = mapper.byId(id).orElse(null);
      if (task == null) continue;
      try {
        SendReceipt receipt = publisher.publish(task);
        if (mapper.markTriggerPublished(id, TriggerStrategy.ROCKETMQ.name(), Instant.now()) == 1) {
          mapper.insertEvent(id, "TASK_TRIGGER_PUBLISHED", TaskTriggerSource.ROCKETMQ.name(),
            null, null, "RocketMQ send acknowledged: " + receipt.getMessageId());
        }
      } catch (Exception exception) {
        String message = exception.getMessage() == null
          ? exception.getClass().getSimpleName() : exception.getMessage();
        if (message.length() > 1000) message = message.substring(0, 1000);
        mapper.markTriggerPublishFailure(id, message);
        failed.increment();
      }
    }
  }
}
