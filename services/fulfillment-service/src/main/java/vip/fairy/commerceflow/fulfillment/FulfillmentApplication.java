package vip.fairy.commerceflow.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class FulfillmentApplication {

  public static void main(String[] args) {
    SpringApplication.run(FulfillmentApplication.class, args);
  }
}
