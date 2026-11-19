package vip.fairy.commerceflow.simulator.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("vip.fairy.commerceflow.simulator.payment.mapper")
@SpringBootApplication
@EnableScheduling
public class PaymentSimulatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentSimulatorApplication.class, args);
  }
}
