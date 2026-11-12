package vip.fairy.commerceflow.simulator.logistics;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@MapperScan("vip.fairy.commerceflow.simulator.logistics")
public class LogisticsSimulatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(LogisticsSimulatorApplication.class, args);
  }

}
