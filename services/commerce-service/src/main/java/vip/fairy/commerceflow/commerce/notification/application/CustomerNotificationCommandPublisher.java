package vip.fairy.commerceflow.commerce.notification.application;

public interface CustomerNotificationCommandPublisher {

  void publish(CustomerNotificationCommandV1 command);

  void publishOrderCancelled(String eventId, Long customerId, Long orderId, String reason,
                             String causationId);
}
