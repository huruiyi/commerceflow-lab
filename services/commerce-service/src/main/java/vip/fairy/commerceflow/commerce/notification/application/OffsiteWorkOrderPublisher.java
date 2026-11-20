package vip.fairy.commerceflow.commerce.notification.application;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.notification.application.contract.v1.ReturnWorkOpenedPublisher;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;

@Component
public class OffsiteWorkOrderPublisher implements ReturnWorkOpenedPublisher {

  public static final String TOPIC = "offsite-notifications";
  private final NotificationMapper outbox;
  private final ObjectMapper json;

  public OffsiteWorkOrderPublisher(NotificationMapper outbox, ObjectMapper json) {
    this.outbox = outbox;
    this.json = json;
  }

  @Override
  public void opened(String caseId, long orderId, long merchantId) {
    String id = "offsite-work:" + caseId;
    String payload = json.writeValueAsString(Map.of("version", 1, "eventId", id,
      "type", "RETURN_WORK_OPENED", "recipientId", merchantId, "orderId", orderId, "reference", caseId));
    outbox.insertOutbox(id, TOPIC, payload);
  }
}
