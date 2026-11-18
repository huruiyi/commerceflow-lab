package vip.fairy.commerceflow.commerce.logistics.application;

import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.logistics.domain.ShipmentRecordView;
import vip.fairy.commerceflow.commerce.logistics.infrastructure.ShipmentMapper;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderLogisticsPort;
import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
import org.springframework.stereotype.Service;

@Service
public class LogisticsService {

  private final OrderLogisticsPort orders;
  private final ShipmentMapper shipments;

  public LogisticsService(OrderLogisticsPort orders, ShipmentMapper shipments) {
    this.orders = orders;
    this.shipments = shipments;
  }

  public void prepareShipment(Long orderId, String carrier, String trackingNumber) {
    shipments.prepare(orderId, "CFL-ORDER-" + orderId, carrier, trackingNumber);
  }

  public void attachShipment(Long orderId, LogisticsGatewayClient.Result result) {
    ShipmentRecordView shipment = shipments.byOrder(orderId).orElseThrow();
    shipments.attach(shipment.getId(), result.platformShipmentNo(), result.carrier(),
      result.trackingNumber(), result.status());
  }

  public void updateShipmentDetails(Long orderId, String carrier, String trackingNumber) {
    shipments.updateDetails(orderId, carrier, trackingNumber);
  }

  public LogisticsResponse require(AppUser customer, Long orderId) {
    if (!orders.belongsToCustomer(orderId, customer.getId())) {
      throw new ResourceNotFoundException("Order not found");
    }
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
