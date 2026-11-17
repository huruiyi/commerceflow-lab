package vip.fairy.commerceflow.commerce.ordering.application.port;

public interface OrderEventPublisherPort {

  String append(Long orderId, String eventType, Object payload);
}
