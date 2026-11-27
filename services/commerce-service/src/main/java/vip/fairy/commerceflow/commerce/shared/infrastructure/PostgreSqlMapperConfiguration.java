package vip.fairy.commerceflow.commerce.shared.infrastructure;

import java.util.Properties;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PostgreSqlMapperConfiguration {

  @Bean
  VendorDatabaseIdProvider commerceDatabaseIdProvider() {
    Properties databaseIds = new Properties();
    databaseIds.setProperty("PostgreSQL", "postgresql");
    databaseIds.setProperty("H2", "h2");
    VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
    provider.setProperties(databaseIds);
    return provider;
  }
}
