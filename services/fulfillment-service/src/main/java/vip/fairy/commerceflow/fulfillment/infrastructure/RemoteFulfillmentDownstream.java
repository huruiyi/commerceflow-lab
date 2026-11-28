package vip.fairy.commerceflow.fulfillment.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.fulfillment.application.FulfillmentDownstream;
import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;
import vip.fairy.commerceflow.fulfillment.domain.FulfillmentCommand.PackageLine;
import org.springframework.http.ResponseEntity;

@Component
public class RemoteFulfillmentDownstream implements FulfillmentDownstream {

  private static final String ORDER_PATH = "/internal/v1/orders/store";
  private static final String LOGISTICS_PATH = "/internal/v1/logistics/fulfillment";
  private static final String OUTBOUND_PATH = "/internal/v1/inventory/outbound-documents";
  private final Endpoint order;
  private final Endpoint logistics;
  private final Endpoint inventory;
  private final Endpoint warehouse;
  private final ObjectMapper mapper;
  private final RestClient client;

  public RemoteFulfillmentDownstream(ObjectMapper mapper,
    @Value("${commerceflow.fulfillment.order-endpoint}") String orderEndpoint,
    @Value("${commerceflow.fulfillment.order-secret}") String orderSecret,
    @Value("${commerceflow.fulfillment.logistics-endpoint}") String logisticsEndpoint,
    @Value("${commerceflow.fulfillment.logistics-secret}") String logisticsSecret,
    @Value("${commerceflow.fulfillment.inventory-endpoint}") String inventoryEndpoint,
    @Value("${commerceflow.fulfillment.inventory-secret}") String inventorySecret,
    @Value("${commerceflow.fulfillment.warehouse-endpoint}") String warehouseEndpoint,
    @Value("${commerceflow.fulfillment.warehouse-secret}") String warehouseSecret) {
    this.mapper = mapper;
    this.order = new Endpoint(orderEndpoint, orderSecret);
    this.logistics = new Endpoint(logisticsEndpoint, logisticsSecret);
    this.inventory = new Endpoint(inventoryEndpoint, inventorySecret);
    this.warehouse = new Endpoint(warehouseEndpoint, warehouseSecret);
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(3));
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public List<ShipmentLine> shipmentLines(long merchantId, long orderId) {
    JsonNode reply = post(order, ORDER_PATH + "/query",
      Map.of("type", "SHIPMENT_LINES", "merchantId", merchantId, "orderId", orderId));
    if (!reply.path("found").isBoolean()) throw new IllegalStateException("Invalid Order V1 shipment result");
    if (!reply.path("found").booleanValue()) return List.of();
    if (reply.path("order").path("id").asLong() != orderId || !reply.path("items").isArray()) {
      throw new IllegalStateException("Shipment lines do not match the requested order");
    }
    List<ShipmentLine> lines = new ArrayList<>();
    for (JsonNode item : reply.path("items")) {
      var line = new ShipmentLine(item.path("id").asLong(), item.path("productId").asLong(),
        item.path("quantity").asInt());
      if (item.path("merchantId").asLong() != merchantId || line.orderLineId() <= 0
          || line.productId() <= 0 || line.quantity() <= 0) {
        throw new IllegalStateException("Invalid order shipment line");
      }
      lines.add(line);
    }
    return List.copyOf(lines);
  }

  @Override
  public void postOutbound(ExecutionTask task, List<ShipmentLine> lines) {
    var command = new HashMap<String, Object>();
    command.put("outboundId", task.commandId());
    command.put("orderId", task.orderId());
    command.put("merchantId", task.merchantId());
    command.put("lines", lines);
    if (task.packageShipment()) command.put("partial", true);
    if (task.warehouseId() != null) command.put("warehouseId", task.warehouseId());
    JsonNode receipt = post(inventory, OUTBOUND_PATH, command);
    if (!task.commandId().equals(receipt.path("outboundId").asText())
        || task.orderId() != receipt.path("orderId").asLong() || receipt.path("lineCount").asInt() != lines.size()) {
      throw new IllegalStateException("Invalid inventory outbound receipt");
    }
  }

  @Override
  public PackageReservation reservePackage(ExecutionTask task) {
    var command = new HashMap<String, Object>(Map.of("packageId", task.commandId(),
      "orderId", task.orderId(), "merchantId", task.merchantId(), "carrier", task.carrier(),
      "trackingNumber", task.trackingNumber(), "lines", mapper.readTree(task.packageLines())));
    if (task.warehouseId() != null) command.put("warehouseId", task.warehouseId());
    JsonNode reply = post(order, ORDER_PATH + "/packages/reserve", command);
    if (!reply.path("accepted").isBoolean() || !reply.path("lines").isArray()) {
      throw new IllegalStateException("Invalid order package allocation receipt");
    }
    var lines = new ArrayList<ShipmentLine>();
    for (JsonNode line : reply.path("lines")) {
      lines.add(mapper.treeToValue(line, ShipmentLine.class));
    }
    if (reply.path("accepted").booleanValue() && lines.isEmpty()) {
      throw new IllegalStateException("Accepted package has no reserved lines");
    }
    List<ShipmentLine> actual = null;
    if (reply.path("actualLines").isArray()) {
      actual = new ArrayList<>();
      for (JsonNode line : reply.path("actualLines")) actual.add(mapper.treeToValue(line, ShipmentLine.class));
      actual = List.copyOf(actual);
    }
    return new PackageReservation(reply.path("accepted").booleanValue(), List.copyOf(lines), actual);
  }

  @Override
  public WarehouseDispatch warehouseDispatch(ExecutionTask task, List<ShipmentLine> planned) {
    long warehouseId = warehouseId(task);
    var request = Map.of("outboundId", task.commandId(), "orderId", task.orderId(), "merchantId", task.merchantId(),
      "warehouseId", warehouseId, "wholeOrder", !task.packageShipment(), "lines", planned);
    JsonNode reply = post(warehouse, "/internal/v1/outbounds", request);
    return readWarehouseDispatch(task, planned, reply);
  }

  @Override
  public boolean hasMatchingWarehouseDispatch(ExecutionTask task) {
    try {
      JsonNode reply = post(warehouse, "/internal/v1/outbounds/query", Map.of("outboundId", task.commandId()));
      if (!"DISPATCHED".equals(reply.path("status").asText()) || !reply.hasNonNull("decidedAt")
          || !reply.path("planned").isArray() || reply.path("planned").isEmpty()) {
        return false;
      }
      List<ShipmentLine> planned = mapper.treeToValue(reply.path("planned"), new TypeReference<>() { });
      if (planned.stream().anyMatch(line -> line == null || line.orderLineId() <= 0
          || line.productId() <= 0 || line.quantity() <= 0)) {
        return false;
      }
      if (task.packageShipment()) {
        List<PackageLine> requested = mapper.readValue(task.packageLines(), new TypeReference<>() { });
        var receiptPlan = planned.stream().map(line -> new PackageLine(line.orderLineId(), line.quantity()))
          .sorted(Comparator.comparingLong(PackageLine::orderLineId)).toList();
        if (!receiptPlan.equals(requested)) return false;
      }
      return "DISPATCHED".equals(readWarehouseDispatch(task, planned, reply).status());
    } catch (HttpClientErrorException.NotFound | IllegalStateException | JacksonException failure) {
      return false;
    } catch (RestClientException failure) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Warehouse receipt could not be verified", failure);
    }
  }

  private WarehouseDispatch readWarehouseDispatch(ExecutionTask task, List<ShipmentLine> planned, JsonNode reply) {
    if (!task.commandId().equals(reply.path("outboundId").asText())
        || task.orderId() != reply.path("orderId").asLong() || task.merchantId() != reply.path("merchantId").asLong()
        || warehouseId(task) != reply.path("warehouseId").asLong()) {
      throw new IllegalStateException("Warehouse receipt does not identify the submitted outbound");
    }
    String status = reply.path("status").asText();
    if ("PENDING".equals(status)) return new WarehouseDispatch(status, List.of());
    if (!reply.path("actual").isArray() || planned.size() != reply.path("actual").size()) {
      throw new IllegalStateException("Missing warehouse counts");
    }
    var remaining = new HashMap<Long, Integer>();
    for (ShipmentLine line : planned) remaining.put(line.orderLineId(), line.quantity());
    var counted = new ArrayList<DispatchLine>();
    for (JsonNode item : reply.path("actual")) {
      JsonNode quantity = item.path("quantity");
      long lineId = item.path("orderLineId").longValue();
      Integer maximum = remaining.remove(lineId);
      if (maximum == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt()
          || quantity.intValue() < 0 || quantity.intValue() > maximum
          || (!task.packageShipment() && quantity.intValue() != 0 && quantity.intValue() != maximum)) {
        throw new IllegalStateException("Invalid warehouse count");
      }
      counted.add(new DispatchLine(lineId, quantity.intValue()));
    }
    boolean dispatched = counted.stream().anyMatch(line -> line.quantity() > 0);
    if (!(dispatched ? "DISPATCHED" : "VOIDED").equals(status)
        || (!task.packageShipment() && dispatched && counted.stream().anyMatch(line -> line.quantity() == 0))) {
      throw new IllegalStateException("Warehouse receipt status contradicts the counts");
    }
    return new WarehouseDispatch(status, List.copyOf(counted));
  }

  @Override
  public boolean recordDispatch(ExecutionTask task, List<DispatchLine> actual) {
    JsonNode reply = post(order, ORDER_PATH + "/packages/actual", Map.of("packageId", task.commandId(),
      "orderId", task.orderId(), "merchantId", task.merchantId(), "warehouseId", warehouseId(task),
      "lines", actual)).path("recorded");
    if (!reply.isBoolean()) throw new IllegalStateException("Missing Order V1 dispatch result");
    return reply.booleanValue();
  }

  private long warehouseId(ExecutionTask task) {
    return task.warehouseId() == null ? 1 : task.warehouseId();
  }

  @Override
  public boolean finishPackage(ExecutionTask task) {
    JsonNode reply = post(order, ORDER_PATH + "/packages/finish", Map.of("packageId", task.commandId(),
      "orderId", task.orderId(), "merchantId", task.merchantId())).path("finished");
    if (!reply.isBoolean()) throw new IllegalStateException("Invalid order package completion receipt");
    return reply.booleanValue();
  }

  @Override
  public OrderFacts order(long merchantId, long orderId) {
    JsonNode reply = post(order, ORDER_PATH + "/query",
      Map.of("type", "FIND_MERCHANT", "merchantId", merchantId, "orderId", orderId));
    if (!reply.path("found").isBoolean()) throw new IllegalStateException("Order ownership result is incomplete");
    if (!reply.path("found").booleanValue()) return null;
    JsonNode value = reply.path("order");
    if (value.path("id").asLong() != orderId || !value.path("shipmentVersion").isIntegralNumber()) {
      throw new IllegalStateException("Order snapshot does not match the requested order");
    }
    return new OrderFacts(value.path("orderNumber").asText(null), value.path("status").asText(null),
      value.path("inventoryStatus").asText(null), value.path("carrier").asText(null),
      value.path("trackingNumber").asText(null), value.path("shipmentVersion").longValue());
  }

  @Override
  public boolean mutateOrder(String id, String operation, ExecutionTask task) {
    Map<String, Object> command = new HashMap<>();
    command.put("mutationId", id);
    command.put("type", operation);
    command.put("orderId", task.orderId());
    command.put("actorId", task.merchantId());
    command.put("carrier", task.carrier());
    command.put("trackingNumber", task.trackingNumber());
    command.put("expectedShipmentVersion", task.expectedVersion());
    command.put("detail", task.detail());
    JsonNode changed = post(order, ORDER_PATH + "/mutations", command).path("changed");
    if (!changed.isIntegralNumber() || (changed.longValue() != 0 && changed.longValue() != 1)) {
      throw new IllegalStateException("Invalid Order V1 receipt");
    }
    return changed.intValue() == 1;
  }

  @Override
  public void writeLogistics(String id, String operation, ExecutionTask task) {
    Map<String, Object> command = new HashMap<>();
    command.put("commandId", id);
    command.put("operation", operation);
    command.put("orderId", task.orderId());
    command.put("orderNumber", task.orderNumber());
    command.put("carrier", task.carrier());
    command.put("trackingNumber", task.trackingNumber());
    command.put("expectedVersion", task.expectedVersion());
    if (task.packageShipment()) command.put("packageId", task.commandId());
    JsonNode reply = post(logistics, LOGISTICS_PATH + "/commands", command);
    long expected = task.shipment() ? 0L : task.expectedVersion() + 1;
    if (!id.equals(reply.path("commandId").asText()) || reply.path("platformShipmentNo").asText("").isBlank()
        || !reply.path("shipmentVersion").isIntegralNumber() || reply.path("shipmentVersion").longValue() != expected) {
      throw new IllegalStateException("Invalid Logistics V1 receipt");
    }
  }

  @Override
  public boolean hasShipment(long orderId) {
    JsonNode present = post(logistics, LOGISTICS_PATH + "/shipment-checks", Map.of("orderId", orderId)).path("present");
    if (!present.isBoolean()) throw new IllegalStateException("Invalid logistics lookup result");
    return present.booleanValue();
  }

  private JsonNode post(Endpoint endpoint, String path, Object command) {
    if (endpoint.url().isBlank() || endpoint.secret().isBlank()) {
      throw new IllegalStateException("Fulfillment downstream is not configured");
    }
    String body = mapper.writeValueAsString(command);
    try {
      String timestamp = Long.toString(Instant.now().getEpochSecond());
      String requestId = UUID.randomUUID().toString();
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(body.getBytes(StandardCharsets.UTF_8)));
      String canonical = String.join("\n", "POST", path, timestamp, requestId, hash);
      String signature = hmac(endpoint, canonical);
      ResponseEntity<String> response = client.post().uri(URI.create(endpoint.url() + path))
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-CommerceFlow-Service", "fulfillment-service")
        .header("X-CommerceFlow-Timestamp", timestamp)
        .header("X-CommerceFlow-Request-Id", requestId)
        .header("X-CommerceFlow-Content-SHA256", hash)
        .header("X-CommerceFlow-Signature", signature)
        .body(body).retrieve().toEntity(String.class);
      String payload = response.getBody();
      if (payload == null) throw new IllegalStateException("Missing fulfillment downstream receipt");
      if (endpoint == warehouse) {
        var signatureHeader = response.getHeaders().getFirst("X-CommerceFlow-Receipt-Signature");
        String expected = hmac(endpoint, requestId + "\n" + payload);
        if (signatureHeader == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            signatureHeader.getBytes(StandardCharsets.US_ASCII))) {
          throw new IllegalStateException("Warehouse receipt signature is invalid");
        }
      }
      JsonNode reply = mapper.readTree(payload);
      if (!reply.isObject()) throw new IllegalStateException("Invalid fulfillment downstream receipt");
      return reply;
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Could not sign fulfillment downstream request", exception);
    }
  }

  private String hmac(Endpoint endpoint, String content) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(endpoint.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
  }

  private record Endpoint(String url, String secret) {
  }
}
