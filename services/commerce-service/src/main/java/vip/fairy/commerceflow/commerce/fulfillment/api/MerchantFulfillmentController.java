package vip.fairy.commerceflow.commerce.fulfillment.api;

import java.net.URI;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;
import vip.fairy.commerceflow.commerce.fulfillment.application.contract.FulfillmentCommandV1;
import vip.fairy.commerceflow.commerce.fulfillment.application.contract.FulfillmentCommandV1.PackageLine;
import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentService.CommandResult;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob.Status;
import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentService;
import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentService.ShipmentStatus;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListReadV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListPage;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListQuery;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort.WarehousePlan;

@RestController
@RequestMapping("/api/merchant/orders")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantFulfillmentController {

  private final CurrentUserService users;
  private final MerchantFulfillmentService service;
  private final OrderListReadV1 search;

  public MerchantFulfillmentController(CurrentUserService users, MerchantFulfillmentService service,
                                       OrderListReadV1 search) {
    this.users = users;
    this.service = service;
    this.search = search;
  }

  @GetMapping
  List<MerchantOrderResponse> list(Authentication authentication) {
    return service.list(users.require(authentication).getId());
  }

  @GetMapping("/search")
  OrderListPage<MerchantOrderResponse> search(Authentication authentication,
                                             @RequestParam(defaultValue = "") String q,
                                             @RequestParam(defaultValue = "") String status,
                                             @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "10") int size,
                                             @RequestParam(defaultValue = "en") String lang,
                                             @RequestParam(defaultValue = "UTC") String zone) {
    Long merchantId = users.require(authentication).getId();
    var query = OrderListQuery.from(q, status, from, to, page, size, zone);
    return search.search(null, merchantId, query, lang).map(order -> service.listResponse(order, merchantId));
  }

  @GetMapping("/{id}")
  MerchantOrderResponse get(@PathVariable Long id, Authentication authentication) {
    return service.get(users.require(authentication).getId(), id);
  }

  @GetMapping("/{id}/warehouse-plan")
  WarehousePlan warehousePlan(@PathVariable Long id, Authentication authentication) {
    return service.warehousePlan(users.require(authentication).getId(), id);
  }

  @PostMapping("/fulfillment-v1/commands")
  ResponseEntity<CommandResult> submitCommand(@Valid @RequestBody FulfillmentCommandV1 command,
                                               Authentication authentication) {
    CommandResult result = service.submitCommand(users.require(authentication).getId(), command);
    if (result.status() != Status.PENDING) return ResponseEntity.ok(result);
    URI location = URI.create("/api/merchant/orders/fulfillment-v1/commands/" + result.commandId());
    return ResponseEntity.accepted().location(location).body(result);
  }

  @GetMapping("/fulfillment-v1/commands/{commandId}")
  CommandResult commandStatus(@PathVariable UUID commandId, Authentication authentication) {
    return service.commandStatus(users.require(authentication).getId(), commandId.toString());
  }

  @PostMapping("/{id}/packages")
  ResponseEntity<CommandResult> shipPackage(@PathVariable Long id, @Valid @RequestBody PackageRequest request,
                                            Authentication authentication) {
    Long merchantId = users.require(authentication).getId();
    var command = new FulfillmentCommandV1(1, request.packageId(), FulfillmentCommandV1.Operation.SHIP,
      merchantId, id, Instant.now(), request.carrier(), request.trackingNumber(), null,
      request.lines(), request.warehouseId());
    return submitCommand(command, authentication);
  }

  record PackageRequest(@NotNull UUID packageId, @NotBlank @Size(max = 100) String carrier,
                        @NotBlank @Size(max = 120) String trackingNumber,
                        @NotNull @Size(min = 1, max = 200)
                        List<@NotNull @Valid PackageLine> lines, @Positive Long warehouseId) {
  }

  @GetMapping("/{id}/fulfillment-v1/shipment")
  ShipmentStatus shipmentStatus(@PathVariable Long id, Authentication authentication) {
    return service.shipmentStatus(users.require(authentication).getId(), id);
  }
}
