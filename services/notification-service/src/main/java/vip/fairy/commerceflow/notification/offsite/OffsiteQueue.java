package vip.fairy.commerceflow.notification.offsite;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
class OffsiteQueue {

  private final OffsiteMapper store;
  private final ObjectMapper json;
  private final String customerSms;
  private final String customerEmail;
  private final String merchantSms;
  private final String merchantEmail;

  OffsiteQueue(OffsiteMapper store, ObjectMapper json,
      @Value("${commerceflow.offsite.customer-sms:}") String customerSms,
      @Value("${commerceflow.offsite.customer-email:}") String customerEmail,
      @Value("${commerceflow.offsite.merchant-sms:}") String merchantSms,
      @Value("${commerceflow.offsite.merchant-email:}") String merchantEmail) {
    this.store = store;
    this.json = json;
    this.customerSms = customerSms;
    this.customerEmail = customerEmail;
    this.merchantSms = merchantSms;
    this.merchantEmail = merchantEmail;
  }

  @Transactional
  public void enqueue(OffsiteIntake.Event event) {
    String fingerprint = OffsiteSignature.digest(json.writeValueAsString(event));
    if (store.insertEvent(event, fingerprint) == 0) {
      if (!fingerprint.equals(store.fingerprint(event.eventId()))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Notification event ID reused");
      }
      return;
    }
    String subject = event.refund() ? "退款成功" : "售后工单待处理";
    String message = event.refund() ? "订单 " + event.orderId() + " 已退款 " + event.amount().toPlainString()
      + " " + event.currency() + "。退款单 " + event.reference() + "。"
      : "订单 " + event.orderId() + " 有待处理售后工单 " + event.reference() + "。请到商家后台查看。";
    enqueue(event, "SMS", event.refund() ? customerSms : merchantSms, null, message);
    enqueue(event, "EMAIL", event.refund() ? customerEmail : merchantEmail, subject, message);
  }

  private void enqueue(OffsiteIntake.Event event, String channel, String recipient, String subject, String message) {
    String requestId = UUID.nameUUIDFromBytes(OffsiteSignature.bytes(event.eventId() + ":" + channel)).toString();
    var command = new OffsiteMapper.Command(requestId, channel, recipient, event.eventId(), subject, message);
    store.insertDelivery(command, event.eventId(), recipient.isBlank() ? "SKIPPED" : "PENDING");
  }
}
