package vip.fairy.commerceflow.commerce.ordering.application.port;

import java.time.Instant;

public interface OrderLogisticsPort {

  boolean belongsToCustomer(Long orderId, Long customerId);
  String orderStatus(Long orderId);
  void markDelivered(Long orderId, Instant deliveredAt);

  void statusUpdated(Long orderId, String logisticsStatus, Instant occurredAt,
                     String detail, String triggerSource);
}
