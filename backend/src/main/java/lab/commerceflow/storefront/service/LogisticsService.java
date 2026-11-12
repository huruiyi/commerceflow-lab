package lab.commerceflow.storefront.service;

import lab.commerceflow.storefront.domain.AppUser;
import lab.commerceflow.storefront.domain.ShipmentRecordView;
import lab.commerceflow.storefront.mapper.ShipmentMapper;
import lab.commerceflow.storefront.mapper.ShopOrderMapper;
import lab.commerceflow.storefront.web.dto.LogisticsResponse;
import org.springframework.stereotype.Service;

@Service
public class LogisticsService {
  private final ShopOrderMapper orders;private final ShipmentMapper shipments;
  public LogisticsService(ShopOrderMapper orders,ShipmentMapper shipments){this.orders=orders;this.shipments=shipments;}
  public LogisticsResponse require(AppUser customer,Long orderId){orders.find(customer.getId(),orderId).orElseThrow(()->new ResourceNotFoundException("Order not found"));ShipmentRecordView value=shipments.byOrder(orderId).orElseThrow(()->new ResourceNotFoundException("Logistics not found"));return LogisticsResponse.from(value,shipments.events(value.getId()));}
  public LogisticsResponse find(Long orderId){return shipments.byOrder(orderId).map(value->LogisticsResponse.from(value,shipments.events(value.getId()))).orElse(null);}
}
