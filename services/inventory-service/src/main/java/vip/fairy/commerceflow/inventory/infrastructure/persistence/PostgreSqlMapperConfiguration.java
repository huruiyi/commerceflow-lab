package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import java.util.Properties;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PostgreSqlMapperConfiguration {

  @Bean
  DatabaseIdProvider inventoryDatabaseIdProvider() {
    Properties databaseIds = new Properties();
    databaseIds.setProperty("PostgreSQL", "postgresql");
    VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
    provider.setProperties(databaseIds);
    return provider;
  }
}
