package vip.fairy.commerceflow.commerce.notification.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.notification.domain.DeliveryAudit.Delivery;
import vip.fairy.commerceflow.commerce.notification.domain.DeliveryAudit.Recovery;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class NotificationRecoveryService {

  private final NotificationMapper outbox;

  NotificationRecoveryService(NotificationMapper outbox) {
    this.outbox = outbox;
  }

  @Transactional(readOnly = true)
  public Report get(String eventId) {
    var delivery = require(outbox.delivery(eventId));
    return new Report(delivery, outbox.recoveries(eventId));
  }

  @Transactional
  public Report retry(String eventId, String requestId, long actorId, String reason) {
    var delivery = require(outbox.lockDelivery(eventId));
    var previous = outbox.recovery(eventId, requestId);
    if (previous != null) {
      if (previous.actorId() != actorId || !previous.reason().equals(reason)) {
        throw new BusinessRuleException("Notification retry has different operator or reason");
      }
    } else {
      if (outbox.replayDeadOutbox(eventId) != 1) {
        throw new BusinessRuleException("Only a dead notification delivery can be retried");
      }
      outbox.recordRecovery(delivery, requestId, actorId, reason);
    }
    return get(eventId);
  }

  private Delivery require(Delivery delivery) {
    if (delivery == null) throw new ResourceNotFoundException("Notification delivery not found");
    return delivery;
  }

  public record Report(Delivery delivery, List<Recovery> actions) {
  }
}
