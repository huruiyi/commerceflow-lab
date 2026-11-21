package vip.fairy.commerceflow.messaging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
class MessagingController {

  private final DeliveryLedger ledger;
  private final MessagingAccess access;
  private final ObjectMapper json;
  private final boolean faultsEnabled;

  MessagingController(DeliveryLedger ledger, MessagingAccess access, ObjectMapper json,
                      @Value("${messaging.faults-enabled}") boolean faultsEnabled) {
    this.ledger = ledger;
    this.access = access;
    this.json = json;
    this.faultsEnabled = faultsEnabled;
  }

  @PostMapping("/api/provider/v1/deliveries")
  ResponseEntity<DeliveryLedger.Receipt> submit(HttpServletRequest request, @RequestBody String body) {
    access.sender(request, body);
    DeliveryLedger.Command value;
    try {
      value = json.readValue(body, DeliveryLedger.Command.class);
    } catch (JacksonException failure) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid delivery request JSON");
    }
    return response(ledger.submit(value));
  }

  @GetMapping("/api/provider/v1/deliveries/{id}")
  DeliveryLedger.Receipt query(HttpServletRequest request, @PathVariable String id) {
    access.sender(request, "");
    return ledger.query(id);
  }

  @GetMapping("/api/admin/session")
  Capabilities capabilities(HttpServletRequest request) {
    access.admin(request);
    return new Capabilities(faultsEnabled);
  }

  @GetMapping("/api/admin/deliveries")
  DeliveryLedger.Page list(HttpServletRequest request, @RequestParam String channel,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) String reference,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "20") int size) {
    access.admin(request);
    return ledger.list(channel, status, reference, page, size);
  }

  @GetMapping("/api/admin/deliveries/{id}")
  DeliveryLedger.Receipt detail(HttpServletRequest request, @PathVariable String id) {
    access.admin(request);
    return ledger.query(id);
  }

  @PostMapping("/api/admin/deliveries")
  ResponseEntity<DeliveryLedger.Receipt> test(HttpServletRequest request, @RequestBody DeliveryLedger.Command value) {
    access.admin(request);
    return response(ledger.submit(value));
  }

  @PutMapping("/api/admin/faults/{id}")
  ResponseEntity<Void> fault(HttpServletRequest request, @PathVariable String id, @RequestBody Fault value) {
    access.admin(request);
    ledger.setFault(id, value.behavior());
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<DeliveryLedger.Receipt> response(DeliveryLedger.Submission result) {
    return switch (result.result()) {
      case "THROTTLED" -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, "1").build();
      case "TIMEOUT" -> ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
      default -> ResponseEntity.ok(result.receipt());
    };
  }

  record Capabilities(boolean faultsEnabled) {
  }

  record Fault(String behavior) {
  }
}
