package vip.fairy.commerceflow.payment.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerceflow.payment")
public record PaymentShadowProperties(boolean acceptingTraffic, Shadow shadow) {

  public record Shadow(boolean enabled, int batchSize, Source source) {
  }

  public record Source(String url, String username, String password) {
  }
}
