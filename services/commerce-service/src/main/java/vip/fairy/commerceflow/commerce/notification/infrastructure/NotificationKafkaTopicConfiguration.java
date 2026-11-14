package vip.fairy.commerceflow.commerce.notification.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class NotificationKafkaTopicConfiguration {

  @Bean
  @Profile("!test")
  NewTopic customerNotificationCommands(
    @Value("${commerceflow.notification.delivery.topic:customer-notification-commands}") String topic,
    @Value("${commerceflow.kafka.topic-replicas:1}") int replicas
  ) {
    return TopicBuilder.name(topic).partitions(3).replicas(replicas).build();
  }

  @Bean
  @Profile("!test")
  NewTopic customerNotificationCommandsDlt(
    @Value("${commerceflow.notification.delivery.topic:customer-notification-commands}") String topic,
    @Value("${commerceflow.kafka.topic-replicas:1}") int replicas
  ) {
    return TopicBuilder.name(topic + "-dlt").partitions(3).replicas(replicas).build();
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> notificationKafkaListenerContainerFactory(
    ConsumerFactory<String, String> consumerFactory,
    KafkaTemplate<String, String> kafka,
    @Value("${commerceflow.notification.delivery.topic:customer-notification-commands}") String topic,
    @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup
  ) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
      new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setAutoStartup(autoStartup);
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
      (record, exception) -> new TopicPartition(topic + "-dlt", record.partition()));
    factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L)));
    return factory;
  }
}
