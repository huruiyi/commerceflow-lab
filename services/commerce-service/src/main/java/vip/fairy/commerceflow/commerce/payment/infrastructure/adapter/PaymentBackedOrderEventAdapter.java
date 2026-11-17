package vip.fairy.commerceflow.commerce.payment.infrastructure.adapter;

import org.springframework.stereotype.Component;

import vip.fairy.commerceflow.commerce.ordering.application.port.OrderEffectInboxPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderEventPublisherPort;
import vip.fairy.commerceflow.commerce.payment.application.PaymentEventPipeline;
import vip.fairy.commerceflow.commerce.payment.infrastructure.OutboxMapper;

@Component
public class PaymentBackedOrderEventAdapter implements OrderEventPublisherPort, OrderEffectInboxPort {

  private final PaymentEventPipeline events;
  private final OutboxMapper outbox;

  public PaymentBackedOrderEventAdapter(PaymentEventPipeline events, OutboxMapper outbox) {
    this.events = events;
    this.outbox = outbox;
  }

  @Override
  public String append(Long orderId, String eventType, Object payload) {
    return events.appendOrder(orderId.toString(), eventType, payload);
  }

  @Override
  public boolean claim(String consumerGroup, String eventId, String topic) {
    return outbox.consume(consumerGroup, eventId, topic) == 1;
  }
}
