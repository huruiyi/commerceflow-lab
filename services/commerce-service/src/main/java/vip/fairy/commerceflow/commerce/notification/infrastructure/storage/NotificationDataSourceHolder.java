package vip.fairy.commerceflow.commerce.notification.infrastructure.storage;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

public class NotificationDataSourceHolder implements AutoCloseable {

  private final HikariDataSource dataSource;

  public NotificationDataSourceHolder(NotificationStorageProperties properties) {
    dataSource = new HikariDataSource();
    dataSource.setPoolName("NotificationStoragePool");
    dataSource.setDriverClassName(properties.getDriverClassName());
    dataSource.setJdbcUrl(properties.getUrl());
    dataSource.setUsername(properties.getUsername());
    dataSource.setPassword(properties.getPassword());
    dataSource.setMaximumPoolSize(5);
    dataSource.setMinimumIdle(1);
    dataSource.setConnectionTimeout(3000);
  }

  public DataSource getDataSource() {
    return dataSource;
  }

  @Override
  public void close() {
    dataSource.close();
  }
}
