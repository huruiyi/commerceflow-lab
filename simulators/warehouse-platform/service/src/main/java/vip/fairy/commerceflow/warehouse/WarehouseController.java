package vip.fairy.commerceflow.warehouse;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
class WarehouseController {

  private final WarehouseOutbounds outbounds;
  private final WarehouseReturns returns;
  private final String returnSecret;
  private final ObjectMapper mapper;
  private final String secret;
  private final String token;

  WarehouseController(WarehouseOutbounds outbounds, WarehouseReturns returns, ObjectMapper mapper,
                      @Value("${warehouse.return-secret}") String returnSecret,
                      @Value("${warehouse.fulfillment-secret}") String secret,
                      @Value("${warehouse.admin-token}") String token) {
    if (secret.isBlank() || token.isBlank() || returnSecret.isBlank()) {
      throw new IllegalArgumentException("Warehouse credentials are required");
    }
    this.outbounds = outbounds;
    this.returns = returns;
    this.returnSecret = returnSecret;
    this.mapper = mapper;
    this.secret = secret;
    this.token = token;
  }

  @PostMapping("/internal/v1/outbounds")
  ResponseEntity<String> submit(HttpServletRequest request, @RequestBody String payload) {
    String nonce = verify(request, payload, "fulfillment-service", secret);
    return signed(nonce, outbounds.submit(mapper.readValue(payload, WarehouseOutbounds.Command.class)), secret);
  }

  @PostMapping("/internal/v1/outbounds/query")
  ResponseEntity<String> query(HttpServletRequest request, @RequestBody String payload) {
    String nonce = verify(request, payload, "fulfillment-service", secret);
    return signed(nonce, outbounds.get(mapper.readValue(payload, OutboundReference.class).outboundId()), secret);
  }

  @GetMapping("/api/admin/outbounds")
  List<WarehouseOutbounds.Receipt> list(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestParam(required = false) Long orderId) {
    requireAdmin(auth);
    return outbounds.list(orderId);
  }

  @PutMapping("/api/admin/outbounds/{id}/result")
  WarehouseOutbounds.Receipt decide(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable String id, @RequestBody WarehouseOutbounds.Decision decision) {
    requireAdmin(auth);
    return outbounds.record(id, decision);
  }

  @PostMapping("/internal/v1/returns")
  ResponseEntity<String> submitReturn(HttpServletRequest request, @RequestBody String payload) {
    String nonce = verify(request, payload, "commerce-service", returnSecret);
    return signed(nonce, returns.submit(mapper.readValue(payload, WarehouseReturns.Command.class)), returnSecret);
  }

  @GetMapping("/api/admin/returns")
  List<WarehouseReturns.Receipt> listReturns(@RequestHeader(value = "Authorization", required = false) String auth,
                                             @RequestParam(required = false) Long orderId) {
    requireAdmin(auth);
    return returns.list(orderId);
  }

  @PutMapping("/api/admin/returns/{id}/receipt")
  WarehouseReturns.Receipt receive(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable String id, @RequestBody PhysicalReceipt value) {
    requireAdmin(auth);
    return returns.receive(id, value.warehouseId(), value.quantity(), value.rejected());
  }

  @PutMapping("/api/admin/returns/{id}/quality")
  WarehouseReturns.Receipt inspect(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable String id, @RequestBody Quality value) {
    requireAdmin(auth);
    return returns.inspect(id, value.warehouseId(), value.passed());
  }

  @PutMapping("/api/admin/returns/{id}/supplement")
  WarehouseReturns.Receipt supplement(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @PathVariable String id, @RequestBody Supplement value) {
    requireAdmin(auth);
    return returns.supplement(id, value.warehouseId(), value.quantity(), value.note());
  }

  private record Supplement(long warehouseId, int quantity, String note) {
  }

  private record PhysicalReceipt(long warehouseId, Integer quantity, Boolean rejected) {
  }

  private record Quality(long warehouseId, Boolean passed) {
  }

  private void requireAdmin(String auth) {
    if (!same("Bearer " + token, auth)) throw unauthorized();
  }

  private ResponseEntity<String> signed(String nonce, Object receipt, String key) {
    String json = mapper.writeValueAsString(receipt);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
      .header("X-CommerceFlow-Receipt-Signature", hmac(key, nonce + "\n" + json)).body(json);
  }

  private String verify(HttpServletRequest request, String body, String caller, String key) {
    String timestamp = request.getHeader("X-CommerceFlow-Timestamp");
    String nonce = request.getHeader("X-CommerceFlow-Request-Id");
    try {
      long seconds = Long.parseLong(timestamp);
      long current = Instant.now().getEpochSecond();
      if (seconds < current - 120 || seconds > current + 120
          || nonce == null || !UUID.fromString(nonce).toString().equals(nonce)) {
        throw unauthorized();
      }
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(body)));
      String content = String.join("\n", "POST", request.getRequestURI(), timestamp, nonce, hash);
      if (!caller.equals(request.getHeader("X-CommerceFlow-Service"))
          || !same(hash, request.getHeader("X-CommerceFlow-Content-SHA256"))
          || !same(hmac(key, content), request.getHeader("X-CommerceFlow-Signature"))) throw unauthorized();
      return nonce;
    } catch (IllegalArgumentException error) {
      throw unauthorized();
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("Could not verify warehouse request", error);
    }
  }

  private String hmac(String key, String content) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(utf8(key), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(utf8(content)));
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("Could not sign warehouse receipt", error);
    }
  }

  private static boolean same(String expected, String value) {
    return value != null && MessageDigest.isEqual(utf8(expected), utf8(value));
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }

  private record OutboundReference(String outboundId) {
  }
}
