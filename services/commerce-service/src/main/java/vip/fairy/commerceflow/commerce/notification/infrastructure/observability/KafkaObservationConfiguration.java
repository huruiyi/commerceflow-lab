package vip.fairy.commerceflow.commerce.notification.infrastructure.observability;

import jakarta.annotation.PostConstruct;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
public class KafkaObservationConfiguration {

  private final KafkaTemplate<?, ?> kafkaTemplate;

  public KafkaObservationConfiguration(KafkaTemplate<?, ?> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  @PostConstruct
  void enableObservation() {
    kafkaTemplate.setObservationEnabled(true);
  }
}
