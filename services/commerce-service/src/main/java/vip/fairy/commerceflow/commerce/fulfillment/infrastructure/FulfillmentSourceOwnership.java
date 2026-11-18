package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentSourceOwnership implements ApplicationRunner {

  private final FulfillmentAdmissionMapper admission;
  private final String expectedOwner;

  public FulfillmentSourceOwnership(FulfillmentAdmissionMapper admission,
                                    @Value("${commerceflow.fulfillment.remote.enabled:false}") boolean remote,
                                    MeterRegistry registry) {
    this.admission = admission;
    this.expectedOwner = remote ? "FULFILLMENT" : "COMMERCE";
    Gauge.builder("commerceflow.fulfillment.remote.enabled", this,
      value -> "FULFILLMENT".equals(value.expectedOwner) ? 1 : 0).register(registry);
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (!expectedOwner.equals(admission.writeOwner())) {
      throw new IllegalStateException("Fulfillment route disagrees with the persisted source owner");
    }
  }
}
