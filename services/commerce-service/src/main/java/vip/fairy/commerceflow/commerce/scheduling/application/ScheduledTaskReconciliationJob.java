package vip.fairy.commerceflow.commerce.scheduling.application;

import vip.fairy.commerceflow.commerce.scheduling.infrastructure.SchedulingProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledTaskReconciliationJob {
  private final ScheduledTaskExecutor executor;
  private final SchedulingProperties properties;

  public ScheduledTaskReconciliationJob(ScheduledTaskExecutor executor, SchedulingProperties properties) {
    this.executor = executor;
    this.properties = properties;
  }

  @Scheduled(initialDelayString = "${commerceflow.scheduling.reconciliation.initial-delay:30s}",
    fixedDelayString = "${commerceflow.scheduling.reconciliation.fixed-delay:5m}")
  @SchedulerLock(name = "genericScheduledTaskReconciliation", lockAtLeastFor = "PT0.5S", lockAtMostFor = "PT4M")
  public void run() {
    if (properties.isEnabled()) executor.executeReconciliationBatch(properties.getReconciliation().getBatchSize());
  }
}
