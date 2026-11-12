package lab.commerceflow.storefront.scheduling;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lab.commerceflow.storefront.configuration.SchedulingProperties;
import lab.commerceflow.storefront.configuration.RabbitMqTaskTriggerProperties;
import lab.commerceflow.storefront.domain.ScheduledTaskPolicyView;
import lab.commerceflow.storefront.domain.ScheduledTaskPolicyEventView;
import lab.commerceflow.storefront.mapper.ScheduledTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduledTaskPolicyService {
  private final ScheduledTaskMapper mapper;
  private final SchedulingProperties properties;
  private final ScheduledTaskHandlerRegistry handlers;
  private final RabbitMqTaskTriggerProperties rabbitMq;

  public ScheduledTaskPolicyService(ScheduledTaskMapper mapper, SchedulingProperties properties,
                                    ScheduledTaskHandlerRegistry handlers,
                                    RabbitMqTaskTriggerProperties rabbitMq) {
    this.mapper = mapper;
    this.properties = properties;
    this.handlers = handlers;
    this.rabbitMq = rabbitMq;
  }

  public ResolvedTaskPolicy resolve(String rawTaskType) {
    String taskType = ScheduledTaskTypes.normalize(rawTaskType);
    return mapper.policy(taskType).map(this::fromDatabase).orElseGet(() -> fromConfiguration(taskType));
  }

  public List<ResolvedTaskPolicy> all() {
    Set<String> types = new LinkedHashSet<>(ScheduledTaskTypes.builtIn());
    properties.getTaskPolicies().keySet().stream().map(ScheduledTaskTypes::normalize).forEach(types::add);
    mapper.policies().stream().map(ScheduledTaskPolicyView::getTaskType).forEach(types::add);
    handlers.registeredTypes().forEach(types::add);
    return types.stream().sorted().map(this::resolve).toList();
  }

  @Transactional
  public ResolvedTaskPolicy save(String rawTaskType, boolean enabled, String rawPrimaryTrigger,
                                 boolean reconciliationEnabled, Duration gracePeriod,
                                 Duration claimLease, int maxAttempts, Duration retryDelay,
                                 String updatedBy) {
    String taskType = ScheduledTaskTypes.normalize(rawTaskType);
    TriggerStrategy trigger = parseTrigger(rawPrimaryTrigger);
    validate(gracePeriod, claimLease, maxAttempts, retryDelay);
    if (enabled && handlers.find(taskType).isEmpty()) {
      throw new IllegalStateException("Cannot enable a scheduled task type without a registered handler: " + taskType);
    }
    if (enabled && trigger == TriggerStrategy.RABBITMQ && !rabbitMq.isEnabled()) {
      throw new IllegalStateException("The RABBITMQ trigger adapter is not enabled");
    }
    if (enabled && trigger == TriggerStrategy.ROCKETMQ) {
      throw new IllegalStateException("The " + trigger + " trigger adapter is not installed yet");
    }
    mapper.upsertPolicy(taskType, enabled, trigger.name(), reconciliationEnabled,
        gracePeriod.toSeconds(), claimLease.toSeconds(), maxAttempts, retryDelay.toSeconds(), updatedBy);
    ResolvedTaskPolicy resolved = resolve(taskType);
    audit("UPSERT", resolved, updatedBy);
    return resolved;
  }

  @Transactional
  public ResolvedTaskPolicy reset(String rawTaskType, String updatedBy) {
    String taskType = ScheduledTaskTypes.normalize(rawTaskType);
    mapper.deletePolicy(taskType);
    ResolvedTaskPolicy resolved = resolve(taskType);
    audit("RESET_TO_CONFIG", resolved, updatedBy);
    return resolved;
  }

  public List<ScheduledTaskPolicyEventView> events(String rawTaskType) {
    return mapper.policyEvents(ScheduledTaskTypes.normalize(rawTaskType));
  }

  public void requireTriggerAdapter(ResolvedTaskPolicy policy) {
    if (policy.primaryTrigger() == TriggerStrategy.RABBITMQ && !rabbitMq.isEnabled()) {
      throw new IllegalStateException("The RABBITMQ trigger adapter is not enabled");
    }
    if (policy.primaryTrigger() == TriggerStrategy.ROCKETMQ) {
      throw new IllegalStateException("The ROCKETMQ trigger adapter is not installed yet");
    }
  }

  private void audit(String action, ResolvedTaskPolicy policy, String operatedBy) {
    mapper.insertPolicyEvent(policy.taskType(), action, policy.enabled(), policy.primaryTrigger().name(),
        policy.reconciliationEnabled(), policy.gracePeriodSeconds(), policy.claimLeaseSeconds(),
        policy.maxAttempts(), policy.retryDelaySeconds(), policy.version(), operatedBy);
  }

  private ResolvedTaskPolicy fromDatabase(ScheduledTaskPolicyView policy) {
    return new ResolvedTaskPolicy(policy.getTaskType(), properties.isEnabled() && policy.isEnabled(),
        parseTrigger(policy.getPrimaryTrigger()), policy.isReconciliationEnabled(),
        policy.getGracePeriodSeconds(), policy.getClaimLeaseSeconds(), policy.getMaxAttempts(),
        policy.getRetryDelaySeconds(), policy.getConfigVersion(), "DATABASE_OVERRIDE",
        handlers.find(policy.getTaskType()).isPresent());
  }

  private ResolvedTaskPolicy fromConfiguration(String taskType) {
    SchedulingProperties.Policy configured = properties.getTaskPolicies().entrySet().stream()
        .filter(entry -> ScheduledTaskTypes.normalize(entry.getKey()).equals(taskType))
        .map(java.util.Map.Entry::getValue).findFirst().orElse(properties.getDefaultPolicy());
    return new ResolvedTaskPolicy(taskType, properties.isEnabled() && configured.isEnabled(),
        parseTrigger(configured.getPrimaryTrigger()), configured.isReconciliationEnabled(),
        configured.getGracePeriod().toSeconds(), configured.getClaimLease().toSeconds(),
        configured.getMaxAttempts(), configured.getRetryDelay().toSeconds(), 0,
        properties.getTaskPolicies().keySet().stream()
            .map(ScheduledTaskTypes::normalize).anyMatch(taskType::equals)
                ? "CONFIG_TASK_POLICY" : "CONFIG_DEFAULT",
        handlers.find(taskType).isPresent());
  }

  private TriggerStrategy parseTrigger(String value) {
    try {
      return TriggerStrategy.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (Exception exception) {
      throw new IllegalArgumentException("Unsupported primary trigger: " + value);
    }
  }

  private void validate(Duration gracePeriod, Duration claimLease, int maxAttempts, Duration retryDelay) {
    if (gracePeriod == null || gracePeriod.isNegative()) throw new IllegalArgumentException("Grace period must be non-negative");
    if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) throw new IllegalArgumentException("Claim lease must be positive");
    if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("Max attempts must be between 1 and 100");
    if (retryDelay == null || retryDelay.isNegative()) throw new IllegalArgumentException("Retry delay must be non-negative");
  }
}
