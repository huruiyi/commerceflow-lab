package lab.commerceflow.storefront.scheduling;

import lab.commerceflow.storefront.service.OrderCancellationService;
import org.springframework.stereotype.Component;

@Component
public class PaymentTimeoutTaskHandler implements ScheduledTaskHandler {
  private final OrderCancellationService cancellations;

  public PaymentTimeoutTaskHandler(OrderCancellationService cancellations) {
    this.cancellations = cancellations;
  }

  @Override
  public String taskType() {
    return ScheduledTaskTypes.PAYMENT_TIMEOUT;
  }

  @Override
  public void execute(ScheduledTaskExecution task) {
    cancellations.cancelExpired(Long.valueOf(task.aggregateId()), task.taskId());
  }
}
