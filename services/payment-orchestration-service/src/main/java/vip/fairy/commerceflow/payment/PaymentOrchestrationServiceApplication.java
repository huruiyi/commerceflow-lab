package vip.fairy.commerceflow.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(PaymentShadowProperties.class)
public class PaymentOrchestrationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentOrchestrationServiceApplication.class, args);
  }
}
