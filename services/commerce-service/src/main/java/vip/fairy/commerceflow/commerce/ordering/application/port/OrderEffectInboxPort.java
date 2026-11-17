package vip.fairy.commerceflow.commerce.ordering.application.port;

public interface OrderEffectInboxPort {

  boolean claim(String consumerGroup, String eventId, String topic);
}
