package vip.fairy.commerceflow.simulator.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("vip.fairy.commerceflow.simulator.payment.mapper")
@SpringBootApplication
public class PaymentSimulatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(PaymentSimulatorApplication.class, args);
  }
}
