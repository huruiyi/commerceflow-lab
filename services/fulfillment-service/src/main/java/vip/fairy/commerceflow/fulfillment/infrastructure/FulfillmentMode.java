package vip.fairy.commerceflow.fulfillment.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentMode implements ApplicationRunner {

  private final ShadowDatabases databases;
  private final String configured;
  private final boolean requirePromotedMode;
  private volatile boolean ready;

  public FulfillmentMode(ShadowDatabases databases, @Value("${commerceflow.fulfillment.mode}") String configured,
                         @Value("${commerceflow.fulfillment.require-promoted-mode}") boolean requirePromotedMode) {
    this.databases = databases;
    this.configured = configured;
    this.requirePromotedMode = requirePromotedMode;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (!"SHADOW".equals(configured) && !"EXECUTION".equals(configured)) {
      throw new IllegalStateException("Unknown fulfillment runtime mode");
    }
    try (var target = databases.target()) {
      ExecutionMapper mapper = target.getMapper(ExecutionMapper.class);
      if (!requirePromotedMode) mapper.initializeMode(configured);
      if (!configured.equals(mapper.mode())) {
        throw new IllegalStateException("Fulfillment database is already bound to a different mode");
      }
      target.commit();
      ready = true;
    }
  }

  public boolean shadow() {
    return ready && "SHADOW".equals(configured);
  }

  public boolean execution() {
    return ready && "EXECUTION".equals(configured);
  }
}
