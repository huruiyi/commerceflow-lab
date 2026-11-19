package vip.fairy.commerceflow.order.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMonitoringMapper {

  Backlog backlog();

  record Backlog(long shipmentIntents, long oldestShipmentIntentSeconds, long cancellationRecoveryRequired) {
  }
}
