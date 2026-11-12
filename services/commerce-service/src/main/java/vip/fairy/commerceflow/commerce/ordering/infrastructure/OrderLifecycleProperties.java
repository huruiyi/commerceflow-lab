package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "commerceflow.order-lifecycle")
public class OrderLifecycleProperties {
  private Duration paymentTimeout = Duration.ofMinutes(30);
  private Duration autoReceiveAfterDelivery = Duration.ofDays(7);
  private Duration autoCompleteAfterReceipt = Duration.ofDays(7);

  public Duration getPaymentTimeout() {
    return paymentTimeout;
  }

  public void setPaymentTimeout(Duration paymentTimeout) {
    this.paymentTimeout = paymentTimeout;
  }

  public Duration getAutoReceiveAfterDelivery() {
    return autoReceiveAfterDelivery;
  }

  public void setAutoReceiveAfterDelivery(Duration value) {
    this.autoReceiveAfterDelivery = value;
  }

  public Duration getAutoCompleteAfterReceipt() {
    return autoCompleteAfterReceipt;
  }

  public void setAutoCompleteAfterReceipt(Duration value) {
    this.autoCompleteAfterReceipt = value;
  }

  @AssertTrue(message = "order lifecycle durations must be positive")
  public boolean isDurationsValid() {
    return positive(paymentTimeout) && positive(autoReceiveAfterDelivery)
      && positive(autoCompleteAfterReceipt);
  }

  private boolean positive(Duration value) {
    return value != null && !value.isZero() && !value.isNegative();
  }
}
