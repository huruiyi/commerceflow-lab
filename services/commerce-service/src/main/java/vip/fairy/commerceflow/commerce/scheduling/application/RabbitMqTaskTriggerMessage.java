package vip.fairy.commerceflow.commerce.scheduling.application;

import java.time.Instant;

public record RabbitMqTaskTriggerMessage(Long taskId, String taskType, Instant dueAt,
                                         String strategySnapshot) {
}
