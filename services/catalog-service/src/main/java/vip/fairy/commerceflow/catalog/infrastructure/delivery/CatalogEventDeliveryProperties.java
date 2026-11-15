package vip.fairy.commerceflow.catalog.infrastructure.delivery;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "commerceflow.catalog-event-delivery")
public class CatalogEventDeliveryProperties {

  private boolean enabled = true;
  @NotBlank
  private String inventoryBaseUrl = "http://localhost:18081";
  @NotBlank
  private String secret = "demo-catalog-internal-secret";
  @Min(1)
  private int maxAttempts = 5;
  @Min(1)
  private int batchSize = 50;
  @NotNull
  private Duration retryDelay = Duration.ofSeconds(5);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getInventoryBaseUrl() {
    return inventoryBaseUrl;
  }

  public void setInventoryBaseUrl(String inventoryBaseUrl) {
    this.inventoryBaseUrl = inventoryBaseUrl;
  }

  public String getSecret() {
    return secret;
  }

  public void setSecret(String secret) {
    this.secret = secret;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public Duration getRetryDelay() {
    return retryDelay;
  }

  public void setRetryDelay(Duration retryDelay) {
    this.retryDelay = retryDelay;
  }
}
