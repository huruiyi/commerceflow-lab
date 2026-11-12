package vip.fairy.commerceflow.commerce.scheduling.application;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record ScheduledTaskExecution(
  Long taskId,
  String taskKey,
  String taskType,
  String aggregateType,
  String aggregateId,
  JsonNode payload,
  Instant dueAt,
  int attempt,
  TaskTriggerSource triggerSource) {
}
