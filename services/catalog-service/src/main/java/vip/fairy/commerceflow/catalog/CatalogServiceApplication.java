package vip.fairy.commerceflow.catalog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import vip.fairy.commerceflow.catalog.infrastructure.delivery.CatalogEventDeliveryProperties;

@SpringBootApplication
@EnableScheduling
@MapperScan("vip.fairy.commerceflow.catalog.infrastructure.persistence")
@EnableConfigurationProperties(CatalogEventDeliveryProperties.class)
public class CatalogServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CatalogServiceApplication.class, args);
  }
}
