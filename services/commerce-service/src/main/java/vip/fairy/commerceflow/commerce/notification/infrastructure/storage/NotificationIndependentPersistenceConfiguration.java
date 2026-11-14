package vip.fairy.commerceflow.commerce.notification.infrastructure.storage;

import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.independent.StoreMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(prefix = "commerceflow.notification.storage", name = "enabled",
  havingValue = "true")
public class NotificationIndependentPersistenceConfiguration {

  @Bean(destroyMethod = "close")
  NotificationDataSourceHolder notificationDataSourceHolder(
    NotificationStorageProperties properties
  ) {
    return new NotificationDataSourceHolder(properties);
  }

  @Bean(name = "notificationFlyway", initMethod = "migrate")
  Flyway notificationFlyway(NotificationDataSourceHolder dataSource) {
    return Flyway.configure()
      .dataSource(dataSource.getDataSource())
      .locations("classpath:db/notification-schema")
      .baselineOnMigrate(true)
      .baselineVersion("0")
      .load();
  }

  @Bean
  @DependsOn("notificationFlyway")
  NotificationSessionHolder notificationSessionHolder(
    NotificationDataSourceHolder dataSource
  ) throws Exception {
    return new NotificationSessionHolder(dataSource);
  }

  @Bean
  StoreMapper notificationStoreMapper(NotificationSessionHolder sessions) {
    SqlSessionTemplate template = new SqlSessionTemplate(sessions.getSqlSessionFactory());
    return template.getMapper(StoreMapper.class);
  }

  @Bean(name = "notificationTransactionManager")
  PlatformTransactionManager notificationTransactionManager(
    NotificationDataSourceHolder dataSource
  ) {
    return new DataSourceTransactionManager(dataSource.getDataSource());
  }
}
