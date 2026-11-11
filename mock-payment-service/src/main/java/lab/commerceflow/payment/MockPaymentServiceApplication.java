package lab.commerceflow.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("lab.commerceflow.payment.mapper")
@SpringBootApplication
public class MockPaymentServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MockPaymentServiceApplication.class, args);
  }
}
