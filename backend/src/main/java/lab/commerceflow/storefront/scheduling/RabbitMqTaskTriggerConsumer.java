package lab.commerceflow.storefront.scheduling;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import lab.commerceflow.storefront.domain.ScheduledTaskView;
import lab.commerceflow.storefront.mapper.ScheduledTaskMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "commerceflow.scheduling.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMqTaskTriggerConsumer {
  private final ObjectMapper objectMapper; private final ScheduledTaskMapper mapper;
  private final ScheduledTaskExecutor executor; private final Counter consumed,ignored,malformed;
  public RabbitMqTaskTriggerConsumer(ObjectMapper objectMapper,ScheduledTaskMapper mapper,
      ScheduledTaskExecutor executor,MeterRegistry registry){this.objectMapper=objectMapper;this.mapper=mapper;this.executor=executor;
    consumed=registry.counter("commerceflow.scheduled.tasks.rabbitmq.consumed");
    ignored=registry.counter("commerceflow.scheduled.tasks.rabbitmq.ignored");
    malformed=registry.counter("commerceflow.scheduled.tasks.rabbitmq.malformed");}

  @RabbitListener(queues="${commerceflow.scheduling.rabbitmq.ready-queue:commerceflow.scheduled-task.ready}")
  public void consume(Message raw) {
    RabbitMqTaskTriggerMessage message;
    try {
      message=objectMapper.readValue(raw.getBody(),RabbitMqTaskTriggerMessage.class);
      if(message.taskId()==null||!"RABBITMQ".equals(message.strategySnapshot())) throw new IllegalArgumentException("Invalid RabbitMQ task trigger payload");
    } catch(IllegalArgumentException exception){malformed.increment();throw new AmqpRejectAndDontRequeueException(exception);}
    catch(Exception exception){malformed.increment();throw new AmqpRejectAndDontRequeueException("Invalid RabbitMQ task trigger",exception);}

    ScheduledTaskView task=mapper.byId(message.taskId()).orElse(null);
    if(task==null||!"RABBITMQ".equals(task.getPrimaryTriggerSnapshot())){ignored.increment();return;}
    if(task.getDueAt().isAfter(Instant.now())){
      mapper.clearTriggerPublished(task.getId(),TriggerStrategy.RABBITMQ.name(),
          "RabbitMQ bucket fired before current database due time");
      ignored.increment();return;
    }
    consumed.increment();
    if(!executor.execute(task.getId(),TaskTriggerSource.RABBITMQ))ignored.increment();
  }
}
