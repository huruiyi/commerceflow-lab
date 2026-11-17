package vip.fairy.commerceflow.payment.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

@Component
public class SourceConnectionFactory {

  private final PaymentShadowProperties properties;

  public SourceConnectionFactory(PaymentShadowProperties properties) {
    this.properties = properties;
  }

  public Connection open() throws SQLException {
    var source = properties.shadow().source();
    return DriverManager.getConnection(source.url(), source.username(), source.password());
  }
}
