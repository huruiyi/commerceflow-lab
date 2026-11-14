package vip.fairy.commerceflow.commerce.scheduling.application;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

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
  private final Tracer tracer;
  private final Propagator propagator;
  private final Counter published;

  public RocketMqTaskTriggerPublisher(
    Producer producer,
    ClientServiceProvider provider,
    ObjectMapper mapper,
    RocketMqTaskTriggerProperties properties,
    MeterRegistry registry,
    Tracer tracer,
    Propagator propagator
  ) {
    this.producer = producer;
    this.provider = provider;
    this.mapper = mapper;
    this.properties = properties;
    this.tracer = tracer;
    this.propagator = propagator;
    this.published = registry.counter("commerceflow.scheduled.tasks.rocketmq.published");
  }

  public SendReceipt publish(ScheduledTaskView task) throws Exception {
    Span span = tracer.spanBuilder().name("rocketmq scheduled task publish")
      .kind(Span.Kind.PRODUCER).tag("messaging.system", "rocketmq")
      .tag("commerceflow.task.id", task.getId().toString()).start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      return publish(task, span);
    } catch (Exception exception) {
      span.error(exception);
      throw exception;
    } finally {
      span.end();
    }
  }

  private SendReceipt publish(ScheduledTaskView task, Span span) throws Exception {
    RocketMqTaskTriggerMessage payload = new RocketMqTaskTriggerMessage(task.getId(),
      task.getTaskType(), task.getDueAt(), task.getPrimaryTriggerSnapshot());
    var builder = provider.newMessageBuilder()
      .setTopic(properties.getTopic())
      .setTag(properties.getTag())
      .setKeys("scheduled-task-" + task.getId())
      .setDeliveryTimestamp(task.getDueAt().toEpochMilli())
      .setBody(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
    propagator.inject(span.context(), builder,
      (carrier, key, value) -> carrier.addProperty(key, value));
    Message message = builder.build();
    SendReceipt receipt = producer.send(message);
    published.increment();
    return receipt;
  }
}
