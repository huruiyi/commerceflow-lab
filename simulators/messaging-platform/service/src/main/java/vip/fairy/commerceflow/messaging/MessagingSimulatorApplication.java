package vip.fairy.commerceflow.messaging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MessagingSimulatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(MessagingSimulatorApplication.class, args);
  }
}
