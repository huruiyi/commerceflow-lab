package vip.fairy.commerceflow.notification.infrastructure.persistence;

import java.util.Properties;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("postgresql")
class PostgreSqlMapperConfiguration {

  @Bean
  VendorDatabaseIdProvider notificationDatabaseIdProvider() {
    Properties databaseIds = new Properties();
    databaseIds.setProperty("PostgreSQL", "postgresql");
    VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
    provider.setProperties(databaseIds);
    return provider;
  }
}
