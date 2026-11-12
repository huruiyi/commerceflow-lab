package lab.commerceflow.storefront.scheduling;

import lab.commerceflow.storefront.service.OrderReceiptLifecycleService;
import org.springframework.stereotype.Component;

@Component
public class AutoReceiveTaskHandler implements ScheduledTaskHandler {
  private final OrderReceiptLifecycleService lifecycle;
  public AutoReceiveTaskHandler(OrderReceiptLifecycleService lifecycle){this.lifecycle=lifecycle;}
  @Override public String taskType(){return ScheduledTaskTypes.AUTO_RECEIVE;}
  @Override public void execute(ScheduledTaskExecution task){lifecycle.autoReceive(Long.valueOf(task.aggregateId()),task.taskId());}
}
