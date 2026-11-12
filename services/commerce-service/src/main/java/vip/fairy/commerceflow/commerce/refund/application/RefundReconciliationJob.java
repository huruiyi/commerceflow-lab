package vip.fairy.commerceflow.commerce.refund.application;

import io.micrometer.core.instrument.*;
import vip.fairy.commerceflow.commerce.refund.infrastructure.RefundMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RefundReconciliationJob {
  private final RefundMapper refunds;
  private final RefundGatewayClient gateway;
  private final Counter attempts;
  private final Counter failures;

  public RefundReconciliationJob(RefundMapper refunds, RefundGatewayClient gateway, MeterRegistry registry) {
    this.refunds = refunds;
    this.gateway = gateway;
    this.attempts = registry.counter("commerceflow.refund.reconciliation.attempts");
    this.failures = registry.counter("commerceflow.refund.reconciliation.failures");
    Gauge.builder("commerceflow.refund.processing", refunds, mapper -> mapper.pendingReconciliation().size()).register(registry);
  }

  @Scheduled(fixedDelayString = "${commerceflow.refund-reconciliation.delay:60000}", initialDelayString = "${commerceflow.refund-reconciliation.initial-delay:30000}")
  @SchedulerLock(name = "refundReconciliation", lockAtLeastFor = "PT1S", lockAtMostFor = "PT55S")
  public void reconcile() {
    for (var refund : refunds.pendingReconciliation()) {
      attempts.increment();
      try {
        var payment = refunds.paymentById(refund.getPaymentId()).orElseThrow();
        gateway.requestNotificationRedelivery(refund, payment);
      } catch (Exception exception) {
        failures.increment();
      }
    }
  }
}
