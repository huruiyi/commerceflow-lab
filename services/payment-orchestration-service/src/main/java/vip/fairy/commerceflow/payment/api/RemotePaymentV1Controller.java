package vip.fairy.commerceflow.payment.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.payment.application.PaymentLedgerReportService;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService.ContractReceipt;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService.ProtocolConflictException;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService.ProtocolNotFoundException;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService.RecoveryRequest;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentCloseCommandV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentCloseResultV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentCreateCommandV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentSnapshotV1;
import vip.fairy.commerceflow.payment.application.contract.v1.RefundQueryV1;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;

@RestController
@RequestMapping("/internal/v1/payments")
public class RemotePaymentV1Controller {

  private final PaymentShadowProperties properties;
  private final RemotePaymentProtocolService protocol;
  private final PaymentLedgerReportService reports;
  private final ObjectMapper mapper;

  public RemotePaymentV1Controller(PaymentShadowProperties properties,
                                   RemotePaymentProtocolService protocol,
                                   PaymentLedgerReportService reports,
                                   ObjectMapper mapper) {
    this.properties = properties;
    this.protocol = protocol;
    this.reports = reports;
    this.mapper = mapper;
  }

  @PostMapping("/create")
  PaymentSnapshotV1 create(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    return protocol.create(mapper.readValue(payload, PaymentCreateCommandV1.class));
  }

  @PostMapping("/query")
  Map<String,Object> query(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    PaymentQueryV1 query = mapper.readValue(payload, PaymentQueryV1.class);
    query.validate();
    Map<String,Object> response = new LinkedHashMap<>();
    response.put("payment", protocol.latest(query).orElse(null));
    response.put("refund", protocol.refund(query).orElse(null));
    return response;
  }

  @PostMapping("/ledger-reports")
  PaymentLedgerReportService.Report ledgerReport(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    PaymentLedgerReportService.Query query;
    try {
      query = mapper.readerFor(PaymentLedgerReportService.Query.class)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(payload);
    } catch (JacksonException invalid) {
      throw new IllegalArgumentException("Payment ledger report request is invalid", invalid);
    }
    return reports.summarize(query);
  }

  @PostMapping("/refund-query")
  Object refundQuery(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    return protocol.refund(mapper.readValue(payload, RefundQueryV1.class)).orElse(null);
  }

  @PostMapping("/close")
  PaymentCloseResultV1 close(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    return protocol.close(mapper.readValue(payload, PaymentCloseCommandV1.class));
  }

  @PostMapping("/results")
  ContractReceipt result(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    return protocol.result(mapper.readValue(payload, PaymentResultV1.class));
  }

  @GetMapping("/recovery")
  List<Map<String,Object>> recoverable() {
    requireTraffic();
    return protocol.recoverable();
  }

  @PostMapping("/recovery/{processId}")
  ContractReceipt recover(@PathVariable String processId, @RequestBody String payload,
                          HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    return protocol.recover(processId, mapper.readValue(payload, RecoveryRequest.class));
  }

  @ExceptionHandler(ProtocolConflictException.class)
  ResponseEntity<Map<String,String>> conflict(ProtocolConflictException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
      "code", "PAYMENT_PROTOCOL_CONFLICT", "message", exception.getMessage()));
  }

  @ExceptionHandler(ProtocolNotFoundException.class)
  ResponseEntity<Map<String,String>> notFound(ProtocolNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
      "code", "PAYMENT_PROTOCOL_NOT_FOUND", "message", exception.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String,String>> invalid(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(Map.of(
      "code", "PAYMENT_PROTOCOL_INVALID", "message", exception.getMessage()));
  }

  private void requireTrafficAndHash(String payload, HttpServletRequest request) {
    requireTraffic();
    String actual = sha256(payload);
    if (!actual.equals(request.getHeader("X-CommerceFlow-Content-SHA256"))) {
      throw new IllegalArgumentException("Payment payload hash is invalid");
    }
  }

  private void requireTraffic() {
    if (!properties.acceptingTraffic()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Payment cutover gate is closed; shadow replication remains available");
    }
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
