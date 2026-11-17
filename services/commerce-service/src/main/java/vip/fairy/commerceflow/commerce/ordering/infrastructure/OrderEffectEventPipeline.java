package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderEffectInboxPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderEventPublisherPort;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEffectOutboxEvent;

@Service
public class OrderEffectEventPipeline implements OrderEventPublisherPort, OrderEffectInboxPort {

  private final OrderEffectMapper events;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper;

  public OrderEffectEventPipeline(OrderEffectMapper events,
                                  KafkaTemplate<String, String> kafka,
                                  ObjectMapper mapper) {
    this.events = events;
    this.kafka = kafka;
    this.mapper = mapper;
  }

  @Override
  public String append(Long orderId, String eventType, Object payload) {
    try {
      String eventId = UUID.randomUUID().toString();
      events.insert(eventId, orderId.toString(), eventType, "order-events",
        mapper.writeValueAsString(payload));
      return eventId;
    } catch (Exception exception) {
      throw new IllegalArgumentException("Order effect payload cannot be serialized", exception);
    }
  }

  @Override
  public boolean claim(String consumerGroup, String eventId, String topic) {
    return events.claim(consumerGroup, eventId, topic) == 1;
  }

  @Scheduled(
    fixedDelayString = "${commerceflow.order-effect-outbox.delay:1000}",
    initialDelayString = "${commerceflow.order-effect-outbox.initial-delay:1000}"
  )
  public void publish() {
    for (OrderEffectOutboxEvent event : events.pending()) {
      try {
        kafka.send(event.getTopic(), event.getAggregateId(), envelope(event))
          .get(5, TimeUnit.SECONDS);
        events.published(event.getId());
      } catch (Exception exception) {
        events.retry(event.getId());
      }
    }
  }

  private String envelope(OrderEffectOutboxEvent event) throws Exception {
    return mapper.writeValueAsString(Map.of(
      "eventId", event.getEventId(),
      "eventType", event.getEventType(),
      "aggregateId", event.getAggregateId(),
      "payload", mapper.readTree(event.getPayload())
    ));
  }
}
