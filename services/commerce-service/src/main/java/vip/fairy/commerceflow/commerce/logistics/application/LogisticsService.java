package vip.fairy.commerceflow.commerce.logistics.application;

import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.logistics.domain.ShipmentRecordView;
import vip.fairy.commerceflow.commerce.logistics.infrastructure.ShipmentMapper;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.ShopOrderMapper;
import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
import org.springframework.stereotype.Service;

@Service
public class LogisticsService {

  private final ShopOrderMapper orders;
  private final ShipmentMapper shipments;

  public LogisticsService(ShopOrderMapper orders, ShipmentMapper shipments) {
    this.orders = orders;
    this.shipments = shipments;
  }

  public LogisticsResponse require(AppUser customer, Long orderId) {
    orders.find(customer.getId(), orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    ShipmentRecordView value = shipments.byOrder(orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Logistics not found"));
    return LogisticsResponse.from(value, shipments.events(value.getId()));
  }

  public LogisticsResponse find(Long orderId) {
    return shipments.byOrder(orderId)
      .map(value -> LogisticsResponse.from(value, shipments.events(value.getId())))
      .orElse(null);
  }
}
