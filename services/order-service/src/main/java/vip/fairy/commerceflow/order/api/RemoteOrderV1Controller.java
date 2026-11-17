package vip.fairy.commerceflow.order.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.order.application.RemoteOrderProtocolService;
import vip.fairy.commerceflow.order.application.RemoteOrderProtocolService.ContractReceipt;
import vip.fairy.commerceflow.order.application.RemoteOrderProtocolService.ProtocolConflictException;
import vip.fairy.commerceflow.order.application.RemoteOrderProtocolService.RecoveryRequest;
import vip.fairy.commerceflow.order.application.contract.v1.OrderCommandV1;
import vip.fairy.commerceflow.order.application.contract.v1.OrderResultV1;
import vip.fairy.commerceflow.order.infrastructure.OrderShadowProperties;

@RestController
@RequestMapping("/internal/v1/orders")
public class RemoteOrderV1Controller {

  private final OrderShadowProperties properties;
  private final RemoteOrderProtocolService protocol;
  private final ObjectMapper mapper;

  public RemoteOrderV1Controller(OrderShadowProperties properties,
                                 RemoteOrderProtocolService protocol,
                                 ObjectMapper mapper) {
    this.properties = properties;
    this.protocol = protocol;
    this.mapper = mapper;
  }

  @PostMapping("/commands")
  ContractReceipt command(@RequestBody String payload, HttpServletRequest request) {
    requireTraffic();
    requirePayloadHash(payload, request);
    return protocol.handle(mapper.readValue(payload, OrderCommandV1.class));
  }

  @PostMapping("/results")
  ContractReceipt result(@RequestBody String payload, HttpServletRequest request) {
    requireTraffic();
    requirePayloadHash(payload, request);
    return protocol.handle(mapper.readValue(payload, OrderResultV1.class));
  }

  @GetMapping("/recovery")
  List<Map<String,Object>> recoverable() {
    requireTraffic();
    return protocol.recoverable();
  }

  @PostMapping("/recovery/{processId}")
  ContractReceipt recover(@PathVariable String processId, @RequestBody String payload,
                          HttpServletRequest request) {
    requireTraffic();
    requirePayloadHash(payload, request);
    return protocol.recover(processId, mapper.readValue(payload, RecoveryRequest.class));
  }

  @ExceptionHandler(ProtocolConflictException.class)
  ResponseEntity<Map<String,String>> conflict(ProtocolConflictException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
      "code", "ORDER_PROTOCOL_CONFLICT", "message", exception.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String,String>> invalid(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(Map.of(
      "code", "ORDER_PROTOCOL_INVALID", "message", exception.getMessage()));
  }

  private void requireTraffic() {
    if (!properties.acceptingTraffic()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
      "Order cutover gate is closed; shadow replication remains available");
  }

  private void requirePayloadHash(String payload, HttpServletRequest request) {
    try {
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(StandardCharsets.UTF_8)));
      if (!actual.equals(request.getHeader("X-CommerceFlow-Content-SHA256"))) {
        throw new IllegalArgumentException("Order payload hash is invalid");
      }
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
