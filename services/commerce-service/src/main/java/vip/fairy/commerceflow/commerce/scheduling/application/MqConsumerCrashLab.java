package vip.fairy.commerceflow.commerce.scheduling.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

import vip.fairy.commerceflow.commerce.scheduling.infrastructure.MqConsumerCrashLabProperties;
import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskView;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.ScheduledTaskMapper;
import org.springframework.stereotype.Service;

@Service
public class MqConsumerCrashLab {
  public static final String PAUSED_EVENT = "TASK_DELIVERY_PAUSED_BEFORE_ACK";

  private final MqConsumerCrashLabProperties properties;
  private final ScheduledTaskMapper mapper;
  private final MeterRegistry registry;
  private final AtomicBoolean paused = new AtomicBoolean();

  public MqConsumerCrashLab(MqConsumerCrashLabProperties properties, ScheduledTaskMapper mapper,
                            MeterRegistry registry) {
    this.properties = properties;
    this.mapper = mapper;
    this.registry = registry;
  }

  public void pauseAfterDelivery(ScheduledTaskView task, TaskTriggerSource source) {
    if (!properties.isEnabled() || task.getId() != properties.getTaskId()
      || !paused.compareAndSet(false, true)) return;

    registry.counter("commerceflow.scheduled.tasks.mq.delivery.paused", "broker", source.name())
      .increment();
    mapper.insertEvent(task.getId(), PAUSED_EVENT, source.name(), task.getStatus(), task.getStatus(),
      "Crash lab paused after broker delivery and before task claim/acknowledgement");
    try {
      Thread.sleep(properties.getPause().toMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Crash lab pause was interrupted before acknowledgement", exception);
    }
  }
}
