package lab.commerceflow.storefront.web.dto;

import java.time.Instant;
import java.util.List;
import lab.commerceflow.storefront.domain.ShipmentEventView;
import lab.commerceflow.storefront.domain.ShipmentRecordView;

public record LogisticsResponse(String merchantShipmentNo,String platformShipmentNo,String carrier,String trackingNumber,
    String status,Instant shippedAt,Instant deliveredAt,List<Event> events){
  public static LogisticsResponse from(ShipmentRecordView value,List<ShipmentEventView> events){return value==null?null:new LogisticsResponse(value.getMerchantShipmentNo(),value.getPlatformShipmentNo(),value.getCarrier(),value.getTrackingNumber(),value.getStatus(),value.getShippedAt(),value.getDeliveredAt(),events.stream().map(Event::from).toList());}
  public record Event(String eventId,String eventType,String status,String location,String description,Instant occurredAt){static Event from(ShipmentEventView value){return new Event(value.getEventId(),value.getEventType(),value.getStatus(),value.getLocation(),value.getDescription(),value.getOccurredAt());}}
}
