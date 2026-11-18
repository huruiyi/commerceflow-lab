package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentSourceOwnership implements ApplicationRunner {

  private final FulfillmentAdmissionMapper admission;
  private final String expectedOwner;

  public FulfillmentSourceOwnership(FulfillmentAdmissionMapper admission,
                                    @Value("${commerceflow.fulfillment.remote.enabled:false}") boolean remote) {
    this.admission = admission;
    this.expectedOwner = remote ? "FULFILLMENT" : "COMMERCE";
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (!expectedOwner.equals(admission.writeOwner())) {
      throw new IllegalStateException("Fulfillment route disagrees with the persisted source owner");
    }
  }
}
