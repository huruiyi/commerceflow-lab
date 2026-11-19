package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentSourceOwnership implements ApplicationRunner {

  private final FulfillmentOwnershipMapper ownership;

  public FulfillmentSourceOwnership(FulfillmentOwnershipMapper ownership, MeterRegistry registry) {
    this.ownership = ownership;
    Gauge.builder("commerceflow.fulfillment.remote.enabled", () -> 1).register(registry);
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (!"FULFILLMENT".equals(ownership.writeOwner())) {
      throw new IllegalStateException("Fulfillment route disagrees with the persisted source owner");
    }
  }
}
