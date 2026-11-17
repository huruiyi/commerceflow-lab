package vip.fairy.commerceflow.order.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

@Component
public class SourceConnectionFactory {

  private final OrderShadowProperties properties;

  public SourceConnectionFactory(OrderShadowProperties properties) {
    this.properties = properties;
  }

  public Connection open() throws SQLException {
    var source = properties.shadow().source();
    return DriverManager.getConnection(source.url(), source.username(), source.password());
  }
}
