package vip.fairy.commerceflow.commerce.ordering.application.port;

import java.time.Instant;

public interface OrderLogisticsPort {

  boolean belongsToCustomer(Long orderId, Long customerId);

  void statusUpdated(Long orderId, String logisticsStatus, Instant occurredAt,
                     String detail, String triggerSource);
}
