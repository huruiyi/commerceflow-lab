package lab.commerceflow.storefront.scheduling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lab.commerceflow.storefront.domain.ScheduledTaskView;
import lab.commerceflow.storefront.domain.ScheduledTaskEventView;
import lab.commerceflow.storefront.mapper.ScheduledTaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduledTaskService {
  private final ScheduledTaskMapper mapper;
  private final ScheduledTaskPolicyService policies;
  private final ScheduledTaskHandlerRegistry handlers;
  private final ObjectMapper objectMapper;

  public ScheduledTaskService(ScheduledTaskMapper mapper, ScheduledTaskPolicyService policies,
                              ScheduledTaskHandlerRegistry handlers, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.policies = policies;
    this.handlers = handlers;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public ScheduledTaskView schedule(String taskType, String taskKey, String aggregateType,
                                    String aggregateId, Instant dueAt, Object payload) {
    String normalizedType = ScheduledTaskTypes.normalize(taskType);
    validateText(taskKey, "Task key", 128);
    validateText(aggregateType, "Aggregate type", 64);
    validateText(aggregateId, "Aggregate id", 128);
    if (dueAt == null) throw new IllegalArgumentException("Due time is required");
    ResolvedTaskPolicy policy = policies.resolve(normalizedType);
    if (!policy.enabled()) throw new IllegalStateException("Scheduled task type is disabled: " + normalizedType);
    return scheduleWithPolicy(normalizedType, taskKey, aggregateType, aggregateId, dueAt, payload, policy);
  }

  @Transactional
  public Optional<ScheduledTaskView> scheduleIfEnabled(String taskType, String taskKey,
      String aggregateType, String aggregateId, Instant dueAt, Object payload) {
    String normalizedType = ScheduledTaskTypes.normalize(taskType);
    ResolvedTaskPolicy policy = policies.resolve(normalizedType);
    if (!policy.enabled()) return Optional.empty();
    return Optional.of(scheduleWithPolicy(normalizedType, taskKey, aggregateType, aggregateId,
        dueAt, payload, policy));
  }

  private ScheduledTaskView scheduleWithPolicy(String normalizedType, String taskKey,
      String aggregateType, String aggregateId, Instant dueAt, Object payload,
      ResolvedTaskPolicy policy) {
    validateText(taskKey, "Task key", 128);
    validateText(aggregateType, "Aggregate type", 64);
    validateText(aggregateId, "Aggregate id", 128);
    if (dueAt == null) throw new IllegalArgumentException("Due time is required");
    if (handlers.find(normalizedType).isEmpty()) {
      throw new IllegalStateException("No handler is registered for scheduled task type: " + normalizedType);
    }
    ScheduledTaskView task = new ScheduledTaskView();
    task.setTaskType(normalizedType);
    task.setTaskKey(taskKey.trim());
    task.setAggregateType(aggregateType.trim());
    task.setAggregateId(aggregateId.trim());
    task.setPayloadJson(writePayload(payload));
    task.setDueAt(dueAt);
    task.setPrimaryTriggerSnapshot(policy.primaryTrigger().name());
    task.setReconciliationEnabledSnapshot(policy.reconciliationEnabled());
    task.setGracePeriodSecondsSnapshot(policy.gracePeriodSeconds());
    task.setClaimLeaseSecondsSnapshot(policy.claimLeaseSeconds());
    task.setMaxAttempts(policy.maxAttempts());
    task.setRetryDelaySecondsSnapshot(policy.retryDelaySeconds());
    task.setPolicyVersionSnapshot(policy.version());
    try {
      mapper.insert(task);
      mapper.insertEvent(task.getId(), "TASK_SCHEDULED", "SYSTEM", null, "SCHEDULED",
          "Task created with " + policy.primaryTrigger() + " policy snapshot");
      return mapper.byId(task.getId()).orElseThrow();
    } catch (DuplicateKeyException duplicate) {
      return mapper.byBusinessKey(normalizedType, taskKey.trim()).orElseThrow(() -> duplicate);
    }
  }

  public List<ScheduledTaskView> list(String taskType, String status, int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 500));
    String normalizedType = taskType == null || taskType.isBlank() ? null : ScheduledTaskTypes.normalize(taskType);
    String normalizedStatus = status == null || status.isBlank() ? null
        : ScheduledTaskStatus.valueOf(status.trim().toUpperCase()).name();
    return mapper.list(normalizedType, normalizedStatus, safeLimit);
  }

  public List<ScheduledTaskEventView> events(Long taskId) {
    if (mapper.byId(taskId).isEmpty()) throw new IllegalArgumentException("Scheduled task not found: " + taskId);
    return mapper.taskEvents(taskId);
  }

  @Transactional
  public ScheduledTaskView cancel(Long id, String reason) {
    if (mapper.cancel(id, reason == null || reason.isBlank() ? "Cancelled by administrator" : reason.trim()) != 1) {
      throw new IllegalStateException("Only pending or failed tasks can be cancelled");
    }
    mapper.insertEvent(id, "TASK_CANCELLED", "ADMIN", null, "CANCELLED", reason == null ? "" : reason.trim());
    return mapper.byId(id).orElseThrow();
  }

  @Transactional
  public ScheduledTaskView retry(Long id) {
    if (mapper.retry(id) != 1) throw new IllegalStateException("Only failed tasks can be retried");
    mapper.insertEvent(id, "TASK_RETRIED", TaskTriggerSource.ADMIN_RETRY.name(), "FAILED", "SCHEDULED", "Administrator retry");
    return mapper.byId(id).orElseThrow();
  }

  JsonNode readPayload(ScheduledTaskView task) {
    try { return objectMapper.readTree(task.getPayloadJson()); }
    catch (JsonProcessingException exception) { throw new IllegalStateException("Stored task payload is invalid", exception); }
  }

  private String writePayload(Object payload) {
    try { return objectMapper.writeValueAsString(payload == null ? java.util.Map.of() : payload); }
    catch (JsonProcessingException exception) { throw new IllegalArgumentException("Task payload cannot be serialized", exception); }
  }

  private void validateText(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    if (value.trim().length() > maxLength) throw new IllegalArgumentException(label + " is too long");
  }
}
