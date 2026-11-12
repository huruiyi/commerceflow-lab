package vip.fairy.commerceflow.commerce.scheduling.application;

import vip.fairy.commerceflow.commerce.scheduling.infrastructure.SchedulingProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DatabaseScheduledTaskJob {
  private final ScheduledTaskExecutor executor;
  private final SchedulingProperties properties;

  public DatabaseScheduledTaskJob(ScheduledTaskExecutor executor, SchedulingProperties properties) {
    this.executor = executor;
    this.properties = properties;
  }

  @Scheduled(initialDelayString = "${commerceflow.scheduling.database-scan.initial-delay:10s}",
    fixedDelayString = "${commerceflow.scheduling.database-scan.fixed-delay:15s}")
  @SchedulerLock(name = "genericDatabaseScheduledTasks", lockAtLeastFor = "PT0.5S", lockAtMostFor = "PT2M")
  public void run() {
    if (properties.isEnabled()) executor.executeDatabaseBatch(properties.getDatabaseScan().getBatchSize());
  }
}
