package lab.commerceflow.storefront.scheduling;

public interface ScheduledTaskHandler {
  String taskType();
  void execute(ScheduledTaskExecution task);
}
