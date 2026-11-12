package lab.commerceflow.storefront.configuration;

import java.util.Map;
import lab.commerceflow.storefront.scheduling.RocketMqTaskTriggerConsumer;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "commerceflow.scheduling.rocketmq", name = "enabled",
    havingValue = "true")
public class RocketMqTaskTriggerConfiguration {
  @Bean
  ClientServiceProvider rocketMqClientServiceProvider() {
    return ClientServiceProvider.loadService();
  }

  @Bean
  ClientConfiguration rocketMqClientConfiguration(RocketMqTaskTriggerProperties properties) {
    return ClientConfiguration.newBuilder()
        .setEndpoints(properties.getEndpoints())
        .setRequestTimeout(properties.getRequestTimeout())
        .enableSsl(false)
        .build();
  }

  @Bean(destroyMethod = "close")
  Producer scheduledTaskRocketMqProducer(ClientServiceProvider provider,
      ClientConfiguration configuration, RocketMqTaskTriggerProperties properties) throws Exception {
    return provider.newProducerBuilder()
        .setClientConfiguration(configuration)
        .setTopics(properties.getTopic())
        .build();
  }

  @Bean(destroyMethod = "close")
  PushConsumer scheduledTaskRocketMqConsumer(ClientServiceProvider provider,
      ClientConfiguration configuration, RocketMqTaskTriggerProperties properties,
      RocketMqTaskTriggerConsumer consumer) throws Exception {
    FilterExpression filter = new FilterExpression(properties.getTag(), FilterExpressionType.TAG);
    return provider.newPushConsumerBuilder()
        .setClientConfiguration(configuration)
        .setConsumerGroup(properties.getConsumerGroup())
        .setSubscriptionExpressions(Map.of(properties.getTopic(), filter))
        .setMessageListener(consumer::consume)
        .build();
  }
}
