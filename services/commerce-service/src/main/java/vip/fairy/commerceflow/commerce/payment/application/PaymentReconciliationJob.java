package vip.fairy.commerceflow.commerce.payment.application;

import vip.fairy.commerceflow.commerce.payment.infrastructure.ReconciliationMapper;
import io.micrometer.core.instrument.*;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PaymentReconciliationJob {
  private final ReconciliationMapper mapper;
  private final Counter detected;

  public PaymentReconciliationJob(ReconciliationMapper mapper, MeterRegistry registry) {
    this.mapper = mapper;
    detected = registry.counter("commerceflow.payment.reconciliation.detected");
    Gauge.builder("commerceflow.payment.reconciliation.open", mapper, ReconciliationMapper::openIssues).register(registry);
  }

  @Scheduled(fixedDelayString = "${commerceflow.reconciliation.delay:60000}", initialDelayString = "${commerceflow.reconciliation.initial-delay:1000}")
  @SchedulerLock(name = "paymentReconciliation", lockAtLeastFor = "PT1S", lockAtMostFor = "PT55S")
  public void reconcile() {
    detected.increment(mapper.detectExpired());
  }
}
