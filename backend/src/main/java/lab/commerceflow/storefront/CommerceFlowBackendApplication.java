package lab.commerceflow.storefront;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import lab.commerceflow.storefront.configuration.SchedulingProperties;
import lab.commerceflow.storefront.configuration.OrderLifecycleProperties;
import lab.commerceflow.storefront.configuration.LogisticsReconciliationProperties;
import lab.commerceflow.storefront.configuration.RabbitMqTaskTriggerProperties;
import lab.commerceflow.storefront.configuration.RocketMqTaskTriggerProperties;
import lab.commerceflow.storefront.configuration.MqConsumerCrashLabProperties;
import lab.commerceflow.storefront.configuration.PaymentCancellationCrashLabProperties;

@SpringBootApplication
@EnableScheduling
@MapperScan("lab.commerceflow.storefront.mapper")
@EnableMethodSecurity
@EnableConfigurationProperties({SchedulingProperties.class, OrderLifecycleProperties.class,
    LogisticsReconciliationProperties.class, RabbitMqTaskTriggerProperties.class,
    RocketMqTaskTriggerProperties.class, MqConsumerCrashLabProperties.class,
    PaymentCancellationCrashLabProperties.class})
public class CommerceFlowBackendApplication {
  public static void main(String[] args) {
    SpringApplication.run(CommerceFlowBackendApplication.class, args);
  }
}
