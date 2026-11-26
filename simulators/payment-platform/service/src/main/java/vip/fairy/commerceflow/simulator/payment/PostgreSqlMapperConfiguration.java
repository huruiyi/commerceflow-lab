package vip.fairy.commerceflow.simulator.payment;

import java.util.Properties;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("postgresql")
class PostgreSqlMapperConfiguration {

  @Bean
  VendorDatabaseIdProvider paymentSimulatorDatabaseIdProvider() {
    Properties vendors = new Properties();
    vendors.setProperty("PostgreSQL", "postgresql");
    VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
    provider.setProperties(vendors);
    return provider;
  }
}
