package vip.fairy.commerceflow.commerce.scheduling.application;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.time.Instant;

import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskView;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.ScheduledTaskMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "commerceflow.scheduling.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMqTaskTriggerConsumer {

  private final ObjectMapper objectMapper;
  private final ScheduledTaskMapper mapper;
  private final ScheduledTaskExecutor executor;
  private final MqConsumerCrashLab crashLab;
  private final Tracer tracer;
  private final Propagator propagator;
  private final Counter consumed;
  private final Counter ignored;
  private final Counter malformed;

  public RabbitMqTaskTriggerConsumer(
    ObjectMapper objectMapper,
    ScheduledTaskMapper mapper,
    ScheduledTaskExecutor executor,
    MqConsumerCrashLab crashLab,
    MeterRegistry registry,
    Tracer tracer,
    Propagator propagator
  ) {
    this.objectMapper = objectMapper;
    this.mapper = mapper;
    this.executor = executor;
    this.crashLab = crashLab;
    this.tracer = tracer;
    this.propagator = propagator;
    consumed = registry.counter("commerceflow.scheduled.tasks.rabbitmq.consumed");
    ignored = registry.counter("commerceflow.scheduled.tasks.rabbitmq.ignored");
    malformed = registry.counter("commerceflow.scheduled.tasks.rabbitmq.malformed");
  }

  @RabbitListener(queues = "${commerceflow.scheduling.rabbitmq.ready-queue:commerceflow.scheduled-task.ready}")
  public void consume(Message raw) {
    Span span = propagator.extract(raw.getMessageProperties().getHeaders(),
        (carrier, key) -> carrier.containsKey(key) ? carrier.get(key).toString() : null)
      .name("rabbitmq scheduled task process").kind(Span.Kind.CONSUMER)
      .tag("messaging.system", "rabbitmq").start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      consumeTraced(raw, span);
    } catch (RuntimeException exception) {
      span.error(exception);
      throw exception;
    } finally {
      span.end();
    }
  }

  private void consumeTraced(Message raw, Span span) {
    RabbitMqTaskTriggerMessage message;
    try {
      message = objectMapper.readValue(raw.getBody(), RabbitMqTaskTriggerMessage.class);
      if (message.taskId() == null || !"RABBITMQ".equals(message.strategySnapshot()))
        throw new IllegalArgumentException("Invalid RabbitMQ task trigger payload");
    } catch (IllegalArgumentException exception) {
      malformed.increment();
      throw new AmqpRejectAndDontRequeueException(exception);
    } catch (Exception exception) {
      malformed.increment();
      throw new AmqpRejectAndDontRequeueException("Invalid RabbitMQ task trigger", exception);
    }
    span.tag("commerceflow.task.id", message.taskId().toString());

    ScheduledTaskView task = mapper.byId(message.taskId()).orElse(null);
    if (task == null || !"RABBITMQ".equals(task.getPrimaryTriggerSnapshot())) {
      ignored.increment();
      return;
    }
    if (task.getDueAt().isAfter(Instant.now())) {
      mapper.clearTriggerPublished(task.getId(), TriggerStrategy.RABBITMQ.name(),
        "RabbitMQ bucket fired before current database due time");
      ignored.increment();
      return;
    }
    consumed.increment();
    crashLab.pauseAfterDelivery(task, TaskTriggerSource.RABBITMQ);
    if (!executor.execute(task.getId(), TaskTriggerSource.RABBITMQ)) ignored.increment();
  }
}
