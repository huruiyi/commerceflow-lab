package lab.commerceflow.storefront.scheduling;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lab.commerceflow.storefront.domain.ScheduledTaskView;
import lab.commerceflow.storefront.mapper.ScheduledTaskMapper;
import org.springframework.stereotype.Service;

@Service
public class ScheduledTaskExecutor {
  private final ScheduledTaskMapper mapper;
  private final ScheduledTaskService tasks;
  private final ScheduledTaskHandlerRegistry handlers;
  private final String owner;
  private final Counter succeeded;
  private final Counter failed;

  public ScheduledTaskExecutor(ScheduledTaskMapper mapper, ScheduledTaskService tasks,
                               ScheduledTaskHandlerRegistry handlers, MeterRegistry registry) {
    this.mapper = mapper;
    this.tasks = tasks;
    this.handlers = handlers;
    this.owner = ownerId();
    this.succeeded = registry.counter("commerceflow.scheduled.tasks.succeeded");
    this.failed = registry.counter("commerceflow.scheduled.tasks.failed");
  }

  public int executeDatabaseBatch(int batchSize) {
    Instant now = Instant.now();
    return executeCandidates(mapper.databaseCandidates(now, batchSize), TaskTriggerSource.DATABASE_SCAN);
  }

  public int executeReconciliationBatch(int batchSize) {
    Instant now = Instant.now();
    return executeCandidates(mapper.reconciliationCandidates(now, batchSize),
        TaskTriggerSource.DATABASE_RECONCILIATION);
  }

  public int recoverExpiredLeases(int batchSize) {
    Instant now = Instant.now();
    int recovered = 0;
    for (Long id : mapper.expiredLeaseIds(now, batchSize)) {
      if (mapper.recoverExpiredLease(id, now) == 1) {
        mapper.insertEvent(id, "TASK_LEASE_EXPIRED", "LEASE_RECOVERY", "PROCESSING", "FAILED",
            "Execution lease expired; task is eligible for retry");
        recovered++;
      }
    }
    return recovered;
  }

  public boolean execute(Long taskId, TaskTriggerSource source) {
    ScheduledTaskView before = mapper.byId(taskId).orElse(null);
    if (before == null) return false;
    Instant now = Instant.now();
    Instant leaseUntil = now.plusSeconds(before.getClaimLeaseSecondsSnapshot());
    if (mapper.claim(taskId, now, leaseUntil, owner, source.name()) != 1) return false;
    ScheduledTaskView claimed = mapper.byId(taskId).orElseThrow();
    mapper.insertEvent(taskId, "TASK_CLAIMED", source.name(), before.getStatus(), "PROCESSING",
        "Attempt " + claimed.getAttempts() + " claimed by " + owner);
    try {
      ScheduledTaskHandler handler = handlers.find(claimed.getTaskType())
          .orElseThrow(() -> new IllegalStateException("No handler registered for " + claimed.getTaskType()));
      handler.execute(new ScheduledTaskExecution(claimed.getId(), claimed.getTaskKey(),
          claimed.getTaskType(), claimed.getAggregateType(), claimed.getAggregateId(),
          tasks.readPayload(claimed), claimed.getDueAt(), claimed.getAttempts(), source));
      if (mapper.succeed(taskId, owner) != 1) throw new IllegalStateException("Task execution lease was lost");
      mapper.insertEvent(taskId, "TASK_SUCCEEDED", source.name(), "PROCESSING", "SUCCEEDED", "Handler completed");
      succeeded.increment();
      return true;
    } catch (Exception exception) {
      String message = truncate(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
      Instant retryAt = Instant.now().plusSeconds(claimed.getRetryDelaySecondsSnapshot());
      mapper.fail(taskId, owner, retryAt, message);
      mapper.insertEvent(taskId, "TASK_FAILED", source.name(), "PROCESSING", "FAILED", message);
      failed.increment();
      return false;
    }
  }

  private int executeCandidates(List<Long> candidates, TaskTriggerSource source) {
    int executed = 0;
    for (Long id : candidates) if (execute(id, source)) executed++;
    return executed;
  }

  private String truncate(String value) {
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }

  private String ownerId() {
    try { return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID(); }
    catch (Exception ignored) { return "commerceflow:" + UUID.randomUUID(); }
  }
}
