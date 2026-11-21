package vip.fairy.commerceflow.notification.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class NotificationKafkaConfiguration {

  @Bean
  @Profile("!test")
  NewTopic customerNotificationCommands(
    @Value("${commerceflow.notification.topic:customer-notification-commands}") String topic,
    @Value("${commerceflow.kafka.topic-replicas:1}") int replicas
  ) {
    return TopicBuilder.name(topic).partitions(3).replicas(replicas).build();
  }

  @Bean
  @Profile("!test")
  NewTopic customerNotificationCommandsDlt(
    @Value("${commerceflow.notification.topic:customer-notification-commands}") String topic,
    @Value("${commerceflow.kafka.topic-replicas:1}") int replicas
  ) {
    return TopicBuilder.name(topic + "-dlt").partitions(3).replicas(replicas).build();
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> notificationKafkaListenerContainerFactory(
    ConsumerFactory<String, String> consumerFactory,
    KafkaTemplate<String, String> kafka,
    MeterRegistry registry,
    @Value("${commerceflow.notification.topic:customer-notification-commands}") String topic,
    @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup
  ) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
      new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setAutoStartup(autoStartup);
    factory.getContainerProperties().setObservationEnabled(true);
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
      (record, exception) -> new TopicPartition(topic + "-dlt", record.partition()));
    recoverer.setFailIfSendResultIsError(true);
    Counter deadLetters = Counter.builder("commerceflow.notification.consumer.dead.letters")
      .description("Notification commands whose dead-letter publication was confirmed by Kafka")
      .register(registry);
    ConsumerAwareRecordRecoverer monitoredRecovery = (record, consumer, failure) -> {
      recoverer.accept(record, consumer, failure);
      deadLetters.increment();
    };
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(monitoredRecovery, new FixedBackOff(1000L, 2L));
    Counter storageRetries = Counter.builder("commerceflow.notification.consumer.storage.retries")
      .description("Notification deliveries blocked by retryable storage failures")
      .register(registry);
    errorHandler.setBackOffFunction((record, failure) -> retryableStorageFailure(failure)
      ? new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS)
      : null);
    errorHandler.setRetryListeners((record, failure, attempt) -> {
      if (retryableStorageFailure(failure)) {
        storageRetries.increment();
      }
    });
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }

  private static boolean retryableStorageFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CannotCreateTransactionException
          || cause instanceof DataAccessResourceFailureException
          || cause instanceof TransientDataAccessException
          || cause instanceof SQLTransientException
          || cause instanceof SQLRecoverableException) {
        return true;
      }
      if (cause instanceof SQLException sql && sql.getSQLState() != null
          && sql.getSQLState().startsWith("08")) {
        return true;
      }
    }
    return false;
  }
}
