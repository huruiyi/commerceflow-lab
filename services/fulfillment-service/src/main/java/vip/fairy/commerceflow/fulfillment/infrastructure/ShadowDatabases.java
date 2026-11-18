package vip.fairy.commerceflow.fulfillment.infrastructure;

import javax.sql.DataSource;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ShadowDatabases {

  private final SqlSessionFactory source;
  private final SqlSessionFactory target;
  private final SqlSessionFactory orders;

  public ShadowDatabases(DataSource target,
                         @Value("${commerceflow.fulfillment.source-url}") String sourceUrl,
                         @Value("${commerceflow.fulfillment.order-url}") String orderUrl,
                         @Value("${commerceflow.fulfillment.reader-password}") String password) {
    this.target = configure("shadow", target);
    this.source = configure("commerce", reader(sourceUrl, password));
    this.orders = configure("orders", reader(orderUrl, password));
  }

  public SqlSession source() {
    return source.openSession(TransactionIsolationLevel.REPEATABLE_READ);
  }

  public SqlSession target() {
    return target.openSession();
  }

  public SqlSession orders() {
    return orders.openSession(TransactionIsolationLevel.REPEATABLE_READ);
  }

  private SqlSessionFactory configure(String name, DataSource dataSource) {
    JdbcTransactionFactory transactions = new JdbcTransactionFactory();
    Configuration configuration = new Configuration(new Environment(name, transactions, dataSource));
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setArgNameBasedConstructorAutoMapping(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    loadMapper(configuration, "mapper/ShadowMapper.xml");
    loadMapper(configuration, "mapper/ExecutionMapper.xml");
    return new SqlSessionFactoryBuilder().build(configuration);
  }

  private void loadMapper(Configuration configuration, String resource) {
    try (var input = new ClassPathResource(resource).getInputStream()) {
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Cannot load fulfillment shadow mapper", exception);
    }
  }

  private DataSource reader(String url, String password) {
    return new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, "fulfillment_shadow", password);
  }
}
