package vip.fairy.commerceflow.commerce;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.SchedulingProperties;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.OrderLifecycleProperties;
import vip.fairy.commerceflow.commerce.logistics.infrastructure.LogisticsReconciliationProperties;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.RabbitMqTaskTriggerProperties;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.RocketMqTaskTriggerProperties;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.MqConsumerCrashLabProperties;
import vip.fairy.commerceflow.commerce.payment.infrastructure.PaymentCancellationCrashLabProperties;
import vip.fairy.commerceflow.commerce.notification.infrastructure.NotificationDeliveryProperties;
import vip.fairy.commerceflow.commerce.notification.infrastructure.projection.NotificationProjectionProperties;

@SpringBootApplication
@EnableScheduling
@MapperScan({
  "vip.fairy.commerceflow.commerce.identity.infrastructure",
  "vip.fairy.commerceflow.commerce.catalog.infrastructure",
  "vip.fairy.commerceflow.commerce.cart.infrastructure",
  "vip.fairy.commerceflow.commerce.customer.infrastructure",
  "vip.fairy.commerceflow.commerce.ordering.infrastructure",
  "vip.fairy.commerceflow.commerce.inventory.infrastructure",
  "vip.fairy.commerceflow.commerce.payment.infrastructure",
  "vip.fairy.commerceflow.commerce.refund.infrastructure",
  "vip.fairy.commerceflow.commerce.fulfillment.infrastructure",
  "vip.fairy.commerceflow.commerce.logistics.infrastructure",
  "vip.fairy.commerceflow.commerce.review.infrastructure",
  "vip.fairy.commerceflow.commerce.notification.infrastructure",
  "vip.fairy.commerceflow.commerce.scheduling.infrastructure"
})
@EnableMethodSecurity
@EnableConfigurationProperties({SchedulingProperties.class, OrderLifecycleProperties.class,
  LogisticsReconciliationProperties.class, RabbitMqTaskTriggerProperties.class,
  RocketMqTaskTriggerProperties.class, MqConsumerCrashLabProperties.class,
  PaymentCancellationCrashLabProperties.class, NotificationDeliveryProperties.class,
  NotificationProjectionProperties.class})
public class CommerceFlowApplication {

  public static void main(String[] args) {
    SpringApplication.run(CommerceFlowApplication.class, args);
  }
}
