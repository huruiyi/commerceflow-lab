package lab.commerceflow.logistics;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShipmentService {
  private static final List<String> FLOW = List.of(
      "CREATED", "PICKED_UP", "IN_TRANSIT", "AT_STATION", "OUT_FOR_DELIVERY", "DELIVERED");
  private final JdbcTemplate jdbc;
  private final JdbcClient client;
  private final ObjectMapper mapper;
  private final CallbackOutboxService outbox;

  public ShipmentService(JdbcTemplate jdbc, JdbcClient client, ObjectMapper mapper,
      CallbackOutboxService outbox) {
    this.jdbc = jdbc;
    this.client = client;
    this.mapper = mapper;
    this.outbox = outbox;
  }

  @Transactional
  public ShipmentView create(Create request) {
    ShipmentView existing = find(request.merchantShipmentNo());
    if (existing != null) return existing;
    String platformNo = "LGS-" + UUID.randomUUID().toString().replace("-", "")
        .substring(0, 20).toUpperCase();
    jdbc.update("""
        INSERT INTO shipment_records(
          merchant_shipment_no,platform_shipment_no,order_number,carrier,tracking_number,status,callback_url)
        VALUES(?,?,?,?,?,'CREATED',?)
        """, request.merchantShipmentNo(), platformNo, request.orderNumber(), request.carrier(),
        request.trackingNumber(), request.callbackUrl());
    Long id = jdbc.queryForObject(
        "SELECT id FROM shipment_records WHERE merchant_shipment_no=?", Long.class,
        request.merchantShipmentNo());
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
    int updated = jdbc.update("""
        UPDATE shipment_records
        SET resume_status=status,status='EXCEPTION',updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=? AND status=?
        """, current.id(), current.status());
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
    String resumeStatus = jdbc.queryForObject(
        "SELECT resume_status FROM shipment_records WHERE id=?", String.class, current.id());
    if (resumeStatus == null || !FLOW.contains(resumeStatus) || "DELIVERED".equals(resumeStatus)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SHIPMENT_RESUME_STATUS_INVALID");
    }
    int updated = jdbc.update("""
        UPDATE shipment_records
        SET status=resume_status,resume_status=NULL,updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=? AND status='EXCEPTION'
        """, current.id());
    if (updated != 1) throw concurrentTransition();
    insertEvent(current.id(), "RECOVERY", resumeStatus, fallback(location, location(resumeStatus)),
        fallback(description, "Shipment recovered and resumed from " + resumeStatus));
    return require(merchantNo);
  }

  @Transactional
  public ShipmentView update(String merchantNo, String carrier, String trackingNumber) {
    requireForUpdate(merchantNo);
    jdbc.update("""
        UPDATE shipment_records SET carrier=?,tracking_number=?,updated_at=CURRENT_TIMESTAMP(6)
        WHERE merchant_shipment_no=?
        """, carrier, trackingNumber, merchantNo);
    return require(merchantNo);
  }

  public List<Long> automaticCandidates(Instant updatedBefore, int limit) {
    return jdbc.queryForList("""
        SELECT id FROM shipment_records
        WHERE status IN ('CREATED','PICKED_UP','IN_TRANSIT','AT_STATION','OUT_FOR_DELIVERY')
          AND updated_at<=?
        ORDER BY updated_at,id LIMIT ?
        """, Long.class, Timestamp.from(updatedBefore), limit);
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
    int updated = jdbc.update("""
        UPDATE shipment_records SET status=?,updated_at=CURRENT_TIMESTAMP(6)
        WHERE id=? AND status=?
        """, next, current.id(), current.status());
    if (updated != 1) throw concurrentTransition();
    insertEvent(current.id(), "PROGRESS", next, location(next), description(next));
    return require(current.merchantShipmentNo());
  }

  private ShipmentView requireForUpdate(String merchantNo) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT * FROM shipment_records WHERE merchant_shipment_no=? FOR UPDATE", merchantNo);
    if (rows.isEmpty()) throw notFound();
    return map(rows.getFirst());
  }

  private ShipmentView requireForUpdate(Long shipmentId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT * FROM shipment_records WHERE id=? FOR UPDATE", shipmentId);
    if (rows.isEmpty()) throw notFound();
    return map(rows.getFirst());
  }

  private ShipmentView find(String merchantNo) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT * FROM shipment_records WHERE merchant_shipment_no=?", merchantNo);
    return rows.isEmpty() ? null : map(rows.getFirst());
  }

  private ShipmentView map(Map<String, Object> row) {
    Long id = ((Number) row.get("id")).longValue();
    List<ShipmentView.Event> events = client.sql("""
        SELECT event_id,event_type,status,location,description,occurred_at
        FROM shipment_events WHERE shipment_id=:id ORDER BY occurred_at,id
        """).param("id", id).query((rs, n) -> new ShipmentView.Event(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getTimestamp(6).toInstant())).list();
    return new ShipmentView(id, (String) row.get("merchant_shipment_no"),
        (String) row.get("platform_shipment_no"), (String) row.get("order_number"),
        (String) row.get("carrier"), (String) row.get("tracking_number"),
        (String) row.get("status"), (String) row.get("callback_url"),
        ((Timestamp) row.get("created_at")).toInstant(),
        ((Timestamp) row.get("updated_at")).toInstant(), events);
  }

  private void insertEvent(Long id, String eventType, String status, String location,
      String description) {
    String eventId = UUID.randomUUID().toString();
    Instant occurredAt = Instant.now();
    jdbc.update("""
        INSERT INTO shipment_events(
          shipment_id,event_id,event_type,status,location,description,occurred_at)
        VALUES(?,?,?,?,?,?,?)
        """, id, eventId, eventType, status, location, description, Timestamp.from(occurredAt));
    Map<String, Object> shipment = jdbc.queryForMap("SELECT * FROM shipment_records WHERE id=?", id);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notificationId", eventId);
    payload.put("merchantShipmentNo", shipment.get("merchant_shipment_no"));
    payload.put("platformShipmentNo", shipment.get("platform_shipment_no"));
    payload.put("eventType", eventType);
    payload.put("status", status);
    payload.put("carrier", shipment.get("carrier"));
    payload.put("trackingNumber", shipment.get("tracking_number"));
    payload.put("location", location);
    payload.put("description", description);
    payload.put("occurredAt", occurredAt);
    try {
      outbox.enqueue(id, eventId, (String) shipment.get("callback_url"),
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
                       String trackingNumber, String callbackUrl) {}
}
