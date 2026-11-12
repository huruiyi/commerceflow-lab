package vip.fairy.commerceflow.commerce.scheduling.application;

import java.time.Instant;

public record RocketMqTaskTriggerMessage(Long taskId, String taskType, Instant dueAt,
                                         String strategySnapshot) {
}
