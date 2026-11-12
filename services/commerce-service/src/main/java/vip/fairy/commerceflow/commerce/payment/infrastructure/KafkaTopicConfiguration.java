package vip.fairy.commerceflow.commerce.payment.infrastructure;

import java.util.List;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
@Profile("!test")
public class KafkaTopicConfiguration {
  @Bean
  KafkaAdmin.NewTopics paymentTopics(@Value("${commerceflow.kafka.topic-replicas:1}") int replicas) {
    NewTopic[] topics = List.of("payment-events", "order-events", "payment-callback-events", "payment-reconciliation-events", "merchant-notification-events", "payment-events-retry-1m", "payment-events-retry-5m", "payment-events-dlq").stream().map(name -> TopicBuilder.name(name).partitions(3).replicas(replicas).build()).toArray(NewTopic[]::new);
    return new KafkaAdmin.NewTopics(topics);
  }
}
