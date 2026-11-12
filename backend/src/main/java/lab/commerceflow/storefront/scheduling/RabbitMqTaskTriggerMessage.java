package lab.commerceflow.storefront.scheduling;

import java.time.Instant;

public record RabbitMqTaskTriggerMessage(Long taskId, String taskType, Instant dueAt,
                                         String strategySnapshot) {}
