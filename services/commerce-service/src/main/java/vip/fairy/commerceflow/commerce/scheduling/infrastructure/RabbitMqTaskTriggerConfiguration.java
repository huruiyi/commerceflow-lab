package vip.fairy.commerceflow.commerce.scheduling.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "commerceflow.scheduling.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMqTaskTriggerConfiguration {
  @Bean
  Declarables scheduledTaskRabbitMqTopology(RabbitMqTaskTriggerProperties properties) {
    DirectExchange delay = new DirectExchange(properties.getDelayExchange(), true, false);
    DirectExchange ready = new DirectExchange(properties.getReadyExchange(), true, false);
    DirectExchange dead = new DirectExchange(properties.getDeadExchange(), true, false);
    Queue readyQueue = QueueBuilder.durable(properties.getReadyQueue())
      .deadLetterExchange(properties.getDeadExchange())
      .deadLetterRoutingKey(properties.getDeadRoutingKey()).build();
    Queue deadQueue = QueueBuilder.durable(properties.getDeadQueue()).build();
    List<Declarable> declarations = new ArrayList<>(List.of(delay, ready, dead, readyQueue, deadQueue,
      BindingBuilder.bind(readyQueue).to(ready).with(properties.getReadyRoutingKey()),
      BindingBuilder.bind(deadQueue).to(dead).with(properties.getDeadRoutingKey())));
    for (Duration bucket : properties.getDelayBuckets()) {
      long milliseconds = bucket.toMillis();
      if (milliseconds <= 0 || milliseconds > Integer.MAX_VALUE)
        throw new IllegalStateException("RabbitMQ delay buckets must be between 1ms and " + Integer.MAX_VALUE + "ms");
      String suffix = Long.toString(milliseconds);
      Queue queue = QueueBuilder.durable(properties.getReadyQueue() + ".delay." + suffix)
        .ttl(Math.toIntExact(milliseconds)).deadLetterExchange(properties.getReadyExchange())
        .deadLetterRoutingKey(properties.getReadyRoutingKey()).build();
      Binding binding = BindingBuilder.bind(queue).to(delay).with("bucket." + suffix);
      declarations.add(queue);
      declarations.add(binding);
    }
    return new Declarables(declarations);
  }
}
