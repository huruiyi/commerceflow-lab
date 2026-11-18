package vip.fairy.commerceflow.payment.infrastructure.observability;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;

@Component
public class PaymentRuntimeMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentRuntimeMetrics.class);

  private final PaymentRuntimeMetricsStore store;
  private final AtomicLong outboxPending = new AtomicLong();
  private final AtomicLong commandOutboxPending = new AtomicLong();
  private final AtomicLong processPending = new AtomicLong();
  private final AtomicLong processRecoveryRequired = new AtomicLong();
  private final AtomicLong reconciliationOpen = new AtomicLong();
  private final AtomicLong orphanOpen = new AtomicLong();
  private final AtomicLong refundProcessing = new AtomicLong();
  private final AtomicLong refreshSuccess = new AtomicLong();

  public PaymentRuntimeMetrics(PaymentRuntimeMetricsStore store, PaymentShadowProperties properties,
                               MeterRegistry registry) {
    this.store = store;
    register(registry, "commerceflow.payment.outbox.pending", outboxPending,
      "Payment result Outbox rows waiting for publication");
    register(registry, "commerceflow.payment.command.outbox.pending", commandOutboxPending,
      "Payment command Outbox rows waiting for acknowledgement");
    register(registry, "commerceflow.payment.process.pending", processPending,
      "Payment process managers that have not completed");
    register(registry, "commerceflow.payment.process.recovery.required", processRecoveryRequired,
      "Payment process managers requiring reconciliation or compensation");
    register(registry, "commerceflow.payment.reconciliation.open", reconciliationOpen,
      "Open issues in the payment reconciliation ledger");
    register(registry, "commerceflow.payment.orphan.open", orphanOpen,
      "Open payment shadow orphan quarantine rows");
    register(registry, "commerceflow.payment.refund.processing", refundProcessing,
      "Refunds waiting for a terminal channel result");
    register(registry, "commerceflow.payment.metrics.refresh.success", refreshSuccess,
      "Whether the most recent payment database metric refresh succeeded");
    Gauge.builder("commerceflow.payment.accepting.traffic", properties,
        value -> value.acceptingTraffic() ? 1 : 0)
      .description("Whether Payment V1 accepts ordinary traffic").register(registry);
    Gauge.builder("commerceflow.payment.effects.enabled", properties,
        value -> value.effects() != null && value.effects().enabled() ? 1 : 0)
      .description("Whether payment downstream effects are enabled").register(registry);
    Gauge.builder("commerceflow.payment.shadow.enabled", properties,
        value -> value.shadow() != null && value.shadow().enabled() ? 1 : 0)
      .description("Whether payment source shadow replication is enabled").register(registry);
  }

  @Scheduled(fixedDelayString = "${commerceflow.payment.metrics.refresh-delay:5s}")
  public void refresh() {
    try {
      PaymentRuntimeMetricsStore.Snapshot snapshot = store.snapshot();
      outboxPending.set(snapshot.outboxPending());
      commandOutboxPending.set(snapshot.commandOutboxPending());
      processPending.set(snapshot.processPending());
      processRecoveryRequired.set(snapshot.processRecoveryRequired());
      reconciliationOpen.set(snapshot.reconciliationOpen());
      orphanOpen.set(snapshot.orphanOpen());
      refundProcessing.set(snapshot.refundProcessing());
      refreshSuccess.set(1);
    } catch (RuntimeException exception) {
      refreshSuccess.set(0);
      LOGGER.warn("Could not refresh payment runtime metrics: {}", exception.getMessage());
    }
  }

  private void register(MeterRegistry registry, String name, AtomicLong value,
                        String description) {
    Gauge.builder(name, value, AtomicLong::doubleValue)
      .description(description).register(registry);
  }
}
