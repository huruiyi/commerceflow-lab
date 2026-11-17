package vip.fairy.commerceflow.order.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerceflow.order")
public record OrderShadowProperties(boolean acceptingTraffic, Shadow shadow) {

  public record Shadow(boolean enabled, int batchSize, Source source) {
  }

  public record Source(String url, String username, String password) {
  }
}
