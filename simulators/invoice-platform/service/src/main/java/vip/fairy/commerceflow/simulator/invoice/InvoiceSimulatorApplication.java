package vip.fairy.commerceflow.simulator.invoice;

import java.net.URI;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(InvoiceSimulatorApplication.Settings.class)
public class InvoiceSimulatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(InvoiceSimulatorApplication.class, args);
  }

  @ConfigurationProperties("commerceflow.invoice")
  record Settings(
    String requestSecret,
    String callbackSecret,
    URI callbackUrl,
    int processingDelayMs,
    int timeoutDelayMs,
    int callbackRetryMs
  ) {
    Settings {
      if (requestSecret == null || requestSecret.length() < 32 ||
        callbackSecret == null || callbackSecret.length() < 32 || requestSecret.equals(callbackSecret)) {
        throw new IllegalArgumentException("Configure distinct invoice signing secrets of at least 32 characters");
      }
      if (callbackUrl == null || !("http".equals(callbackUrl.getScheme()) || "https".equals(callbackUrl.getScheme())) ||
        callbackUrl.getHost() == null || callbackUrl.getRawPath().isEmpty() || callbackUrl.getRawQuery() != null ||
        callbackUrl.getRawFragment() != null || callbackUrl.getRawUserInfo() != null) {
        throw new IllegalArgumentException("Configure an invoice callback URL with a host and path");
      }
      if (processingDelayMs < 0 || timeoutDelayMs <= 0 || callbackRetryMs <= 0) {
        throw new IllegalArgumentException("Invalid invoice delay configuration");
      }
    }
  }
}
