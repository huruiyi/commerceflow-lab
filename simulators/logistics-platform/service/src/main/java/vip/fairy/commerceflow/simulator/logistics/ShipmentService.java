package vip.fairy.commerceflow.simulator.logistics;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShipmentService {
  private static final List<String> FLOW = List.of(
    "CREATED", "PICKED_UP", "IN_TRANSIT", "AT_STATION", "OUT_FOR_DELIVERY", "DELIVERED");
  private final LogisticsDataMapper dataMapper;
  private final ObjectMapper mapper;
  private final CallbackOutboxService outbox;

  public ShipmentService(LogisticsDataMapper dataMapper, ObjectMapper mapper,
                         CallbackOutboxService outbox) {
    this.dataMapper = dataMapper;
    this.mapper = mapper;
    this.outbox = outbox;
  }

  @Transactional
  public ShipmentView create(Create request) {
    ShipmentView existing = find(request.merchantShipmentNo());
    if (existing != null) return existing;
    String platformNo = "LGS-" + UUID.randomUUID().toString().replace("-", "")
      .substring(0, 20).toUpperCase();
    dataMapper.insertShipment(request.merchantShipmentNo(), platformNo, request.orderNumber(),
      request.carrier(), request.trackingNumber(), request.callbackUrl());
    Long id = dataMapper.shipmentIdByMerchantNo(request.merchantShipmentNo());
    insertEvent(id, "PROGRESS", "CREATED", "CommerceFlow warehouse", "Shipment created");
    return require(request.merchantShipmentNo());
  }

  @Transactional
  public ShipmentView advance(String merchantNo) {
    return advanceLocked(requireForUpdate(merchantNo));
  }

  @Transactional
  public ShipmentView advanceById(Long shipmentId) {
    return advanceLocked(requireForUpdate(shipmentId));
  }

  @Transactional
  public ShipmentView reportException(String merchantNo, String location, String description) {
    ShipmentView current = requireForUpdate(merchantNo);
    if ("EXCEPTION".equals(current.status())) return current;
    if ("DELIVERED".equals(current.status())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "DELIVERED_SHIPMENT_CANNOT_FAIL");
    }
    if (!FLOW.contains(current.status())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SHIPMENT_CANNOT_REPORT_EXCEPTION");
    }
    int updated = dataMapper.transitionToException(current.id(), current.status());
    if (updated != 1) throw concurrentTransition();
    insertEvent(current.id(), "EXCEPTION", "EXCEPTION", fallback(location, "Carrier exception center"),
      fallback(description, "Shipment requires manual intervention"));
    return require(merchantNo);
  }

  @Transactional
  public ShipmentView recover(String merchantNo, String location, String description) {
    ShipmentView current = requireForUpdate(merchantNo);
    if (!"EXCEPTION".equals(current.status())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SHIPMENT_IS_NOT_EXCEPTION");
    }
    String resumeStatus = dataMapper.resumeStatus(current.id());
    if (resumeStatus == null || !FLOW.contains(resumeStatus) || "DELIVERED".equals(resumeStatus)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SHIPMENT_RESUME_STATUS_INVALID");
    }
    int updated = dataMapper.recoverShipment(current.id());
    if (updated != 1) throw concurrentTransition();
    insertEvent(current.id(), "RECOVERY", resumeStatus, fallback(location, location(resumeStatus)),
      fallback(description, "Shipment recovered and resumed from " + resumeStatus));
    return require(merchantNo);
  }

  @Transactional
  public ShipmentView update(String merchantNo, String carrier, String trackingNumber) {
    requireForUpdate(merchantNo);
    dataMapper.updateShipmentDetails(merchantNo, carrier, trackingNumber);
    return require(merchantNo);
  }

  public List<Long> automaticCandidates(Instant updatedBefore, int limit) {
    return dataMapper.automaticCandidates(updatedBefore, limit);
  }

  public ShipmentView require(String merchantNo) {
    ShipmentView value = find(merchantNo);
    if (value == null) throw notFound();
    return value;
  }

  private ShipmentView advanceLocked(ShipmentView current) {
    int index = FLOW.indexOf(current.status());
    if (index < 0 || index == FLOW.size() - 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SHIPMENT_CANNOT_ADVANCE");
    }
    String next = FLOW.get(index + 1);
    int updated = dataMapper.advanceShipment(current.id(), current.status(), next);
    if (updated != 1) throw concurrentTransition();
    insertEvent(current.id(), "PROGRESS", next, location(next), description(next));
    return require(current.merchantShipmentNo());
  }

  private ShipmentView requireForUpdate(String merchantNo) {
    LogisticsDataMapper.ShipmentRow row = dataMapper.lockShipmentByMerchantNo(merchantNo);
    if (row == null) throw notFound();
    return map(row);
  }

  private ShipmentView requireForUpdate(Long shipmentId) {
    LogisticsDataMapper.ShipmentRow row = dataMapper.lockShipmentById(shipmentId);
    if (row == null) throw notFound();
    return map(row);
  }

  private ShipmentView find(String merchantNo) {
    LogisticsDataMapper.ShipmentRow row = dataMapper.findShipmentByMerchantNo(merchantNo);
    return row == null ? null : map(row);
  }

  private ShipmentView map(LogisticsDataMapper.ShipmentRow row) {
    List<ShipmentView.Event> events = dataMapper.shipmentEvents(row.id()).stream()
      .map(event -> new ShipmentView.Event(
        event.eventId(), event.eventType(), event.status(), event.location(),
        event.description(), event.occurredAt().toInstant()))
      .toList();
    return new ShipmentView(row.id(), row.merchantShipmentNo(), row.platformShipmentNo(),
      row.orderNumber(), row.carrier(), row.trackingNumber(), row.status(), row.callbackUrl(),
      row.createdAt().toInstant(), row.updatedAt().toInstant(), events);
  }

  private void insertEvent(Long id, String eventType, String status, String location,
                           String description) {
    String eventId = UUID.randomUUID().toString();
    Instant occurredAt = Instant.now();
    dataMapper.insertShipmentEvent(id, eventId, eventType, status, location, description, occurredAt);
    LogisticsDataMapper.ShipmentRow shipment = dataMapper.shipmentById(id);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notificationId", eventId);
    payload.put("merchantShipmentNo", shipment.merchantShipmentNo());
    payload.put("platformShipmentNo", shipment.platformShipmentNo());
    payload.put("eventType", eventType);
    payload.put("status", status);
    payload.put("carrier", shipment.carrier());
    payload.put("trackingNumber", shipment.trackingNumber());
    payload.put("location", location);
    payload.put("description", description);
    payload.put("occurredAt", occurredAt);
    try {
      outbox.enqueue(id, eventId, shipment.callbackUrl(),
        mapper.writeValueAsString(payload));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Logistics callback payload cannot be serialized", exception);
    }
  }

  private String location(String status) {
    return switch (status) {
      case "PICKED_UP" -> "Origin warehouse";
      case "IN_TRANSIT" -> "Regional sorting center";
      case "AT_STATION" -> "Destination station";
      case "OUT_FOR_DELIVERY" -> "Local delivery route";
      case "DELIVERED" -> "Recipient address";
      default -> "CommerceFlow warehouse";
    };
  }

  private String description(String status) {
    return switch (status) {
      case "PICKED_UP" -> "Carrier picked up the parcel";
      case "IN_TRANSIT" -> "Parcel is in transit";
      case "AT_STATION" -> "Parcel arrived at destination station";
      case "OUT_FOR_DELIVERY" -> "Courier is delivering the parcel";
      case "DELIVERED" -> "Parcel was delivered";
      default -> status;
    };
  }

  private String fallback(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "SHIPMENT_NOT_FOUND");
  }

  private ResponseStatusException concurrentTransition() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "SHIPMENT_CHANGED_CONCURRENTLY");
  }

  public record Create(String merchantShipmentNo, String orderNumber, String carrier,
                       String trackingNumber, String callbackUrl) {
  }
}
