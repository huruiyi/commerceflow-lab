package vip.fairy.commerceflow.payment.infrastructure.observability;

import org.springframework.stereotype.Repository;
import vip.fairy.commerceflow.payment.infrastructure.persistence.PaymentMetricsMapper;

@Repository
public class PaymentRuntimeMetricsStore {

  private final PaymentMetricsMapper store;

  public PaymentRuntimeMetricsStore(PaymentMetricsMapper store) {
    this.store = store;
  }

  public Snapshot snapshot() {
    return new Snapshot(
      store.outboxPending(),
      store.commandOutboxPending(),
      store.processPending(),
      store.processRecoveryRequired(),
      store.orphanOpen(),
      store.refundProcessing()
    );
  }

  public record Snapshot(long outboxPending, long commandOutboxPending, long processPending,
                         long processRecoveryRequired, long orphanOpen, long refundProcessing) {
  }
}
