package vip.fairy.commerceflow.commerce.notification.application;

public interface CustomerNotificationCommandPublisher {

  void publish(CustomerNotificationCommandV1 command);
}
