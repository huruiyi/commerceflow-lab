package vip.fairy.commerceflow.commerce.scheduling.application;

public interface ScheduledTaskHandler {
  String taskType();

  void execute(ScheduledTaskExecution task);
}
