package vip.fairy.commerceflow.simulator.logistics;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/channel/shipments")
public class ShipmentController {

  private final ShipmentService shipments;
  private final RequestSignatureVerifier signatures;
  private final ObjectMapper mapper;

  public ShipmentController(ShipmentService shipments, RequestSignatureVerifier signatures, ObjectMapper mapper) {
    this.shipments = shipments;
    this.signatures = signatures;
    this.mapper = mapper;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ShipmentView create(@RequestBody String raw, @RequestHeader("X-App-Id") String appId,
                      @RequestHeader("X-Timestamp") String timestamp,
                      @RequestHeader("X-Nonce") String nonce,
                      @RequestHeader("X-Signature") String signature) {
    signatures.verify(appId, timestamp, nonce, signature, raw);
    CreateRequest request = mapper.readValue(raw, CreateRequest.class);
    request.validate();
    return shipments.create(new ShipmentService.Create(request.merchantShipmentNo(), request.orderNumber(), request.carrier(), request.trackingNumber(), request.callbackUrl()));
  }

  @GetMapping
  ShipmentView get(@RequestParam String merchantShipmentNo,
                   @RequestHeader("X-App-Id") String appId,
                   @RequestHeader("X-Timestamp") String timestamp,
                   @RequestHeader("X-Nonce") String nonce,
                   @RequestHeader("X-Signature") String signature) {
    signatures.verify(appId, timestamp, nonce, signature, "");
    return shipments.require(merchantShipmentNo);
  }

  @PutMapping(value = "/{merchantShipmentNo}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ShipmentView update(@PathVariable String merchantShipmentNo,
                      @RequestBody String raw,
                      @RequestHeader("X-App-Id") String appId,
                      @RequestHeader("X-Timestamp") String timestamp,
                      @RequestHeader("X-Nonce") String nonce,
                      @RequestHeader("X-Signature") String signature) throws Exception {
    signatures.verify(appId, timestamp, nonce, signature, raw);
    UpdateRequest request = mapper.readValue(raw, UpdateRequest.class);
    if (request.carrier() == null || request.carrier().isBlank() || request.trackingNumber() == null || request.trackingNumber().isBlank())
      throw new IllegalArgumentException("Carrier and tracking number are required");
    return shipments.update(merchantShipmentNo, request.carrier(), request.trackingNumber());
  }

  @PostMapping("/{merchantShipmentNo}/advance")
  ShipmentView advance(@PathVariable String merchantShipmentNo,
                       @RequestHeader("X-App-Id") String appId,
                       @RequestHeader("X-Timestamp") String timestamp,
                       @RequestHeader("X-Nonce") String nonce,
                       @RequestHeader("X-Signature") String signature) {
    signatures.verify(appId, timestamp, nonce, signature, "");
    return shipments.advance(merchantShipmentNo);
  }

  @PostMapping(value = "/{merchantShipmentNo}/exception", consumes = MediaType.APPLICATION_JSON_VALUE)
  ShipmentView reportException(@PathVariable String merchantShipmentNo,
                               @RequestBody String raw,
                               @RequestHeader("X-App-Id") String appId,
                               @RequestHeader("X-Timestamp") String timestamp,
                               @RequestHeader("X-Nonce") String nonce,
                               @RequestHeader("X-Signature") String signature) throws Exception {
    signatures.verify(appId, timestamp, nonce, signature, raw);
    ExceptionRequest request = mapper.readValue(raw, ExceptionRequest.class);
    return shipments.reportException(merchantShipmentNo, request.location(), request.description());
  }

  @PostMapping(value = "/{merchantShipmentNo}/recover", consumes = MediaType.APPLICATION_JSON_VALUE)
  ShipmentView recover(@PathVariable String merchantShipmentNo,
                       @RequestBody String raw,
                       @RequestHeader("X-App-Id") String appId,
                       @RequestHeader("X-Timestamp") String timestamp,
                       @RequestHeader("X-Nonce") String nonce,
                       @RequestHeader("X-Signature") String signature) throws Exception {
    signatures.verify(appId, timestamp, nonce, signature, raw);
    ExceptionRequest request = mapper.readValue(raw, ExceptionRequest.class);
    return shipments.recover(merchantShipmentNo, request.location(), request.description());
  }

  public record CreateRequest(@NotBlank String merchantShipmentNo, @NotBlank String orderNumber,
                              @NotBlank String carrier, @NotBlank String trackingNumber, @NotBlank String callbackUrl) {
    void validate() {
      if (merchantShipmentNo == null || merchantShipmentNo.isBlank() || orderNumber == null || orderNumber.isBlank() || carrier == null || carrier.isBlank() || trackingNumber == null || trackingNumber.isBlank() || callbackUrl == null || callbackUrl.isBlank())
        throw new IllegalArgumentException("All shipment fields are required");
    }
  }

  public record UpdateRequest(String carrier, String trackingNumber) {
  }

  public record ExceptionRequest(String location, String description) {
  }
}
