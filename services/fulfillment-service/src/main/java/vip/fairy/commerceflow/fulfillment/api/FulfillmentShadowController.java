package vip.fairy.commerceflow.fulfillment.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import vip.fairy.commerceflow.fulfillment.application.FulfillmentExecution;
import vip.fairy.commerceflow.fulfillment.domain.FulfillmentCommand;
import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;
import vip.fairy.commerceflow.fulfillment.infrastructure.FulfillmentMode;
import vip.fairy.commerceflow.fulfillment.application.ShadowReplication;
import vip.fairy.commerceflow.fulfillment.application.ShadowReplication.CommandStatus;
import vip.fairy.commerceflow.fulfillment.application.ShadowReplication.Report;
import vip.fairy.commerceflow.fulfillment.infrastructure.InternalSignature;

@RestController
@RequestMapping("/internal/v1/fulfillment")
public class FulfillmentShadowController {

  private final ShadowReplication shadow;
  private final InternalSignature signature;
  private final ObjectMapper mapper;
  private final FulfillmentExecution execution;
  private final FulfillmentMode mode;

  public FulfillmentShadowController(ShadowReplication shadow, InternalSignature signature, ObjectMapper mapper,
                                     FulfillmentExecution execution, FulfillmentMode mode) {
    this.shadow = shadow;
    this.signature = signature;
    this.mapper = mapper;
    this.execution = execution;
    this.mode = mode;
  }

  @PostMapping("/commands")
  ResponseEntity<CommandStatus> command(@RequestBody String body, HttpServletRequest request) {
    return submit(body, request, false);
  }

  @PostMapping("/legacy-commands")
  ResponseEntity<CommandStatus> legacyCommand(@RequestBody String body, HttpServletRequest request) {
    return submit(body, request, true);
  }

  private ResponseEntity<CommandStatus> submit(String body, HttpServletRequest request, boolean legacy) {
    signature.require(request, body);
    if (!mode.execution()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Fulfillment traffic is closed in shadow mode");
    }
    try {
      FulfillmentCommand command = mapper.readValue(body, FulfillmentCommand.class);
      if (command == null) throw new IllegalArgumentException("Command is required");
      CommandStatus result = execution.accept(command, legacy);
      return "PENDING".equals(result.status()) ? ResponseEntity.accepted().body(result) : ResponseEntity.ok(result);
    } catch (JacksonException | IllegalArgumentException failure) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid fulfillment command", failure);
    }
  }

  @PostMapping("/queries")
  CommandStatus query(@RequestBody String body, HttpServletRequest request) {
    signature.require(request, body);
    Query query = read(body, Query.class);
    if (query == null || query.commandId() == null || query.merchantId() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Command and merchant are required");
    }
    if (!mode.execution() && (query.executionOnly() || !mode.shadow())) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Fulfillment mode is initializing");
    }
    CommandStatus result = mode.execution() ? execution.query(query.commandId(), query.merchantId())
      : shadow.query(query.commandId(), query.merchantId());
    if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fulfillment command not found");
    return result;
  }

  @PostMapping("/shipment-queries")
  ExecutionTask shipment(@RequestBody String body, HttpServletRequest request) {
    signature.require(request, body);
    ShipmentQuery query = read(body, ShipmentQuery.class);
    if (query == null || query.orderId() <= 0 || query.merchantId() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order and merchant are required");
    }
    return execution.shipment(query.orderId(), query.merchantId());
  }

  private <T> T read(String body, Class<T> type) {
    try {
      return mapper.readValue(body, type);
    } catch (JacksonException failure) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid fulfillment query", failure);
    }
  }

  @GetMapping("/shadow/status")
  Report status(HttpServletRequest request) {
    signature.require(request, "");
    Report report = shadow.lastReport();
    if (report == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Shadow is initializing");
    return report;
  }

  @PostMapping("/shadow/snapshot")
  Report snapshot(@RequestBody(required = false) String body, HttpServletRequest request) {
    signature.require(request, body == null ? "" : body);
    requireShadow();
    return shadow.catchUp(true);
  }

  @PostMapping("/shadow/catch-up")
  Report catchUp(@RequestBody(required = false) String body, HttpServletRequest request) {
    signature.require(request, body == null ? "" : body);
    requireShadow();
    return shadow.catchUp(false);
  }

  private void requireShadow() {
    if (!mode.shadow()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Shadow writes are closed");
  }

  record Query(UUID commandId, long merchantId, boolean executionOnly) {
  }

  record ShipmentQuery(long orderId, long merchantId) {
  }
}
