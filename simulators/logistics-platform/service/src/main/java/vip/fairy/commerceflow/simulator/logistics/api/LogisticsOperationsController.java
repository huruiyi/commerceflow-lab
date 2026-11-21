package vip.fairy.commerceflow.simulator.logistics.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.simulator.logistics.application.CallbackOutboxService;
import vip.fairy.commerceflow.simulator.logistics.application.ShipmentService;
import vip.fairy.commerceflow.simulator.logistics.application.TestLogisticsCallbacks;
import vip.fairy.commerceflow.simulator.logistics.application.TestLogisticsCallbacks.Result;
import vip.fairy.commerceflow.simulator.logistics.domain.CallbackOutboxView;
import vip.fairy.commerceflow.simulator.logistics.domain.ShipmentView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/logistics")
public class LogisticsOperationsController {

  private final ShipmentService shipments;
  private final CallbackOutboxService outbox;
  private final byte[] adminAuthorization;
  private final TestLogisticsCallbacks testCallbacks;

  public LogisticsOperationsController(ShipmentService shipments, CallbackOutboxService outbox,
                                       TestLogisticsCallbacks testCallbacks,
                                       @Value("${logistics-platform.admin-token}") String adminToken) {
    this.shipments = shipments;
    this.outbox = outbox;
    this.adminAuthorization = adminToken.isBlank() ? null : ("Bearer " + adminToken).getBytes(StandardCharsets.UTF_8);
    this.testCallbacks = testCallbacks;
  }

  @GetMapping("/session")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void session(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    authorize(authorization);
  }

  @GetMapping("/shipments")
  ShipmentView.Page shipments(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                              @RequestParam(defaultValue = "") String reference,
                              @RequestParam(defaultValue = "") String status,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size) {
    authorize(authorization);
    return shipments.list(reference, status, page, size);
  }

  @GetMapping("/shipments/{merchantNo}")
  ShipmentView shipment(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                        @PathVariable String merchantNo) {
    authorize(authorization);
    return shipments.require(merchantNo);
  }

  @PostMapping("/shipments")
  @ResponseStatus(HttpStatus.CREATED)
  ShipmentView createTestShipment(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @Valid @RequestBody TestShipmentRequest request) {
    authorize(authorization);
    String testNo = TestLogisticsCallbacks.PREFIX + request.requestId();
    String carrier = request.carrier().trim();
    String trackingNumber = request.trackingNumber() == null || request.trackingNumber().isBlank()
      ? testNo : request.trackingNumber().trim();
    String callbackUrl = testCallbacks.callbackUrl(request.rejectCallback() ? Result.REJECT : Result.ACCEPT);
    ShipmentView shipment;
    try {
      shipment = shipments.create(new ShipmentService.Create(testNo, testNo, carrier, trackingNumber, callbackUrl));
    } catch (DuplicateKeyException exception) {
      try {
        shipment = shipments.require(testNo);
      } catch (ResponseStatusException missing) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "TRACKING_NUMBER_ALREADY_IN_USE", exception);
      }
    }
    if (!carrier.equals(shipment.carrier()) || !trackingNumber.equals(shipment.trackingNumber())
        || !callbackUrl.equals(shipment.callbackUrl())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "TEST_SHIPMENT_REQUEST_MISMATCH");
    }
    return shipment;
  }

  @GetMapping("/shipments/{shipmentId}/test-callback-result")
  ResponseEntity<TestCallbackResult> testCallbackResult(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable Long shipmentId) {
    authorize(authorization);
    Result result = testCallbacks.current(shipmentId);
    return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(new TestCallbackResult(result));
  }

  @PutMapping("/shipments/{shipmentId}/test-callback-result")
  TestCallbackResult updateTestCallbackResult(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable Long shipmentId, @Valid @RequestBody TestCallbackResult request) {
    authorize(authorization);
    return new TestCallbackResult(testCallbacks.update(shipmentId, request.result()));
  }

  @PostMapping("/shipments/{merchantNo}/advance")
  ShipmentView advance(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                       @PathVariable String merchantNo) {
    authorize(authorization);
    return shipments.advance(merchantNo);
  }

  @PostMapping("/shipments/{merchantNo}/exception")
  ShipmentView exception(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                         @PathVariable String merchantNo, @Valid @RequestBody TrackNote note) {
    authorize(authorization);
    return shipments.reportException(merchantNo, note.location(), note.description());
  }

  @PostMapping("/shipments/{merchantNo}/recover")
  ShipmentView recover(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                       @PathVariable String merchantNo, @Valid @RequestBody TrackNote note) {
    authorize(authorization);
    return shipments.recover(merchantNo, note.location(), note.description());
  }

  @GetMapping("/callbacks")
  List<CallbackOutboxView> callbacks(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "") String status, @RequestParam(required = false) Long shipmentId) {
    authorize(authorization);
    return outbox.list(status, shipmentId, 500);
  }

  @GetMapping("/callbacks/{id}")
  CallbackOutboxView callback(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @PathVariable Long id) {
    authorize(authorization);
    return outbox.require(id);
  }

  @GetMapping("/callbacks/{id}/events")
  List<CallbackOutboxView.AuditEvent> events(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @PathVariable Long id) {
    authorize(authorization);
    return outbox.events(id);
  }

  @PostMapping("/callbacks/{id}/replay")
  CallbackOutboxView replay(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                            @PathVariable Long id, @Valid @RequestBody ReplayRequest request) {
    authorize(authorization);
    return outbox.replay(id, request.operatedBy(), request.reason());
  }

  private void authorize(String authorization) {
    if (adminAuthorization == null || authorization == null
        || !MessageDigest.isEqual(adminAuthorization, authorization.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "LOGISTICS_ADMIN_TOKEN_INVALID");
    }
  }

  record TrackNote(@Size(max = 160) String location, @Size(max = 300) String description) {
  }

  record TestShipmentRequest(@NotNull UUID requestId, @NotBlank @Size(max = 100) String carrier,
                             @Size(max = 120) String trackingNumber, boolean rejectCallback) {
  }

  record TestCallbackResult(@NotNull Result result) {
  }

  record ReplayRequest(@NotBlank @Size(max = 160) String operatedBy, @NotBlank @Size(max = 500) String reason) {
  }
}
