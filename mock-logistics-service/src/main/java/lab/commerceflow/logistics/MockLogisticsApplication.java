package lab.commerceflow.logistics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MockLogisticsApplication {

  public static void main(String[] args) {
    SpringApplication.run(MockLogisticsApplication.class, args);
  }

}
