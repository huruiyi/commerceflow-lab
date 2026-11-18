package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FulfillmentSqlDialect {

  @Bean
  DatabaseIdProvider databaseIdProvider() {
    return new VendorDatabaseIdProvider();
  }
}
