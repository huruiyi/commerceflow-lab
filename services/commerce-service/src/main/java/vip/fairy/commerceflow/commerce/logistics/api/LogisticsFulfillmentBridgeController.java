package vip.fairy.commerceflow.commerce.logistics.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Command;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Operation;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsFulfillmentV1.Receipt;

@RestController
@RequestMapping("/internal/v1/logistics/fulfillment")
@ConditionalOnProperty(name = "commerceflow.fulfillment.logistics-bridge-enabled", havingValue = "true")
public class LogisticsFulfillmentBridgeController {

  private final LogisticsFulfillmentV1 logistics;
  private final ObjectMapper mapper;
  private final SecretKeySpec key;

  public LogisticsFulfillmentBridgeController(LogisticsFulfillmentV1 logistics, ObjectMapper mapper,
    @Value("${commerceflow.fulfillment.logistics-secret:}") String secret) {
    if (secret.isBlank()) throw new IllegalArgumentException("Logistics fulfillment bridge requires a signing key");
    this.logistics = logistics;
    this.mapper = mapper;
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @PostMapping("/commands")
  Receipt command(@RequestBody String body, HttpServletRequest request) {
    authorize(request, body);
    Command command = mapper.readValue(body, Command.class);
    requireCommand(command);
    return logistics.execute(command);
  }

  @PostMapping("/shipment-checks")
  Map<String, Boolean> check(@RequestBody String body, HttpServletRequest request) {
    authorize(request, body);
    ShipmentCheck query = mapper.readValue(body, ShipmentCheck.class);
    if (query == null || query.orderId() <= 0) throw new IllegalArgumentException("Order ID is required");
    return Map.of("present", logistics.hasShipment(query.orderId()));
  }

  private void requireCommand(Command command) {
    if (command == null || command.commandId() == null
        || !UUID.fromString(command.commandId()).toString().equals(command.commandId())
        || command.orderId() == null || command.orderId() <= 0 || command.operation() == null
        || command.carrier() == null || command.carrier().isBlank() || command.carrier().length() > 100
        || command.trackingNumber() == null || command.trackingNumber().isBlank()
        || command.trackingNumber().length() > 120) throw new IllegalArgumentException("Invalid logistics command");
    if (command.operation() == Operation.ENSURE_SHIPMENT) {
      if (command.expectedVersion() != null || command.orderNumber() == null || command.orderNumber().isBlank()
          || command.orderNumber().length() > 64) throw new IllegalArgumentException("Invalid shipment creation");
    } else if (command.orderNumber() != null || command.expectedVersion() == null
        || command.expectedVersion() < 0 || command.expectedVersion() == Long.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid shipment detail update");
    }
  }

  private void authorize(HttpServletRequest request, String body) {
    if (!verified(request, body)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid logistics fulfillment signature");
    }
  }

  private boolean verified(HttpServletRequest request, String body) {
    String timestamp = request.getHeader("X-CommerceFlow-Timestamp");
    String requestId = request.getHeader("X-CommerceFlow-Request-Id");
    String hash = request.getHeader("X-CommerceFlow-Content-SHA256");
    String signature = request.getHeader("X-CommerceFlow-Signature");
    if (!"fulfillment-service".equals(request.getHeader("X-CommerceFlow-Service"))
        || timestamp == null || requestId == null || hash == null || signature == null) return false;
    try {
      long time = Long.parseLong(timestamp);
      long now = Instant.now().getEpochSecond();
      if (time < now - 300 || time > now + 300) return false;
      UUID.fromString(requestId);
      byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
      if (!MessageDigest.isEqual(expectedHash, HexFormat.of().parseHex(hash))) return false;
      String canonical = String.join("\n", "POST", request.getRequestURI(), timestamp, requestId, hash);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return MessageDigest.isEqual(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
        HexFormat.of().parseHex(signature));
    } catch (IllegalArgumentException exception) {
      return false;
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Could not verify logistics request", exception);
    }
  }

  private record ShipmentCheck(long orderId) {
  }
}
