package vip.fairy.commerceflow.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WarehouseSimulatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(WarehouseSimulatorApplication.class, args);
  }
}
