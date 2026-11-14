package vip.fairy.commerceflow.commerce.notification.infrastructure.storage;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class NotificationSessionHolder {

  private final SqlSessionFactory sqlSessionFactory;

  public NotificationSessionHolder(NotificationDataSourceHolder dataSource) throws Exception {
    SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
    factory.setDataSource(dataSource.getDataSource());
    factory.setMapperLocations(new PathMatchingResourcePatternResolver()
      .getResources("classpath*:mapper/notification-independent/*.xml"));
    org.apache.ibatis.session.Configuration configuration =
      new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    factory.setConfiguration(configuration);
    sqlSessionFactory = factory.getObject();
  }

  public SqlSessionFactory getSqlSessionFactory() {
    return sqlSessionFactory;
  }
}
