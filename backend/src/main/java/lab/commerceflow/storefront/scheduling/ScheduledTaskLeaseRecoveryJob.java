package lab.commerceflow.storefront.scheduling;

import lab.commerceflow.storefront.configuration.SchedulingProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledTaskLeaseRecoveryJob {
  private final ScheduledTaskExecutor executor;
  private final SchedulingProperties properties;

  public ScheduledTaskLeaseRecoveryJob(ScheduledTaskExecutor executor, SchedulingProperties properties) {
    this.executor = executor;
    this.properties = properties;
  }

  @Scheduled(initialDelayString = "${commerceflow.scheduling.lease-recovery.initial-delay:30s}",
      fixedDelayString = "${commerceflow.scheduling.lease-recovery.fixed-delay:30s}")
  @SchedulerLock(name = "genericScheduledTaskLeaseRecovery", lockAtLeastFor = "PT0.5S", lockAtMostFor = "PT25S")
  public void run() {
    if (properties.isEnabled()) {
      executor.recoverExpiredLeases(properties.getLeaseRecovery().getBatchSize());
    }
  }
}
