package lab.commerceflow.storefront;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@EnableScheduling
@MapperScan("lab.commerceflow.storefront.mapper")
@EnableMethodSecurity
public class CommerceFlowBackendApplication {
  public static void main(String[] args) {
    SpringApplication.run(CommerceFlowBackendApplication.class, args);
  }
}
