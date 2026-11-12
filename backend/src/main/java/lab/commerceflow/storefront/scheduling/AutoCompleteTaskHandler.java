package lab.commerceflow.storefront.scheduling;

import lab.commerceflow.storefront.service.OrderReceiptLifecycleService;
import org.springframework.stereotype.Component;

@Component
public class AutoCompleteTaskHandler implements ScheduledTaskHandler {
  private final OrderReceiptLifecycleService lifecycle;
  public AutoCompleteTaskHandler(OrderReceiptLifecycleService lifecycle){this.lifecycle=lifecycle;}
  @Override public String taskType(){return ScheduledTaskTypes.AUTO_COMPLETE;}
  @Override public void execute(ScheduledTaskExecution task){lifecycle.autoComplete(Long.valueOf(task.aggregateId()),task.taskId());}
}
