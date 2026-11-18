package vip.fairy.commerceflow.commerce.fulfillment.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import vip.fairy.commerceflow.commerce.fulfillment.application.FulfillmentRecoveryRoute;
import vip.fairy.commerceflow.commerce.fulfillment.application.FulfillmentRecoveryRoute.Retry;
import vip.fairy.commerceflow.commerce.fulfillment.application.MerchantFulfillmentRecoveryService.Review;
import vip.fairy.commerceflow.commerce.fulfillment.application.ShipmentRecoveryService.ShipmentReview;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentRecovery;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentRecovery;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;

@RestController
@RequestMapping("/api/admin/fulfillment")
@PreAuthorize("hasRole('ADMIN')")
public class FulfillmentRecoveryController {

  private final FulfillmentRecoveryRoute recoveries;
  private final CurrentUserService users;

  public FulfillmentRecoveryController(FulfillmentRecoveryRoute recoveries, CurrentUserService users) {
    this.recoveries = recoveries;
    this.users = users;
  }

  @GetMapping("/shipments")
  List<ShipmentJob> list(@RequestParam(defaultValue = "50") int limit) {
    return recoveries.shipments(limit);
  }

  @GetMapping("/shipments/{id}")
  ShipmentReview detail(@PathVariable Long id) {
    return recoveries.shipment(id);
  }

  @PostMapping("/shipments/{id}/retry")
  ResponseEntity<ShipmentRecovery> retry(@PathVariable Long id, @Valid @RequestBody RetryRequest request,
                                         Authentication authentication) {
    ShipmentRecovery recovery = recoveries.retryShipment(id, request.commandId().toString(),
      new Retry(request.recoveryId().toString(), users.require(authentication).getId(), request.reason(),
        request.evidence()));
    return ResponseEntity.accepted().body(recovery);
  }

  public record RetryRequest(@NotNull UUID recoveryId, @NotNull UUID commandId,
                             @NotBlank @Size(max = 300) String reason,
                             @NotBlank @Size(max = 500) String evidence) {
  }

  @GetMapping("/merchant-operations")
  List<MerchantFulfillmentJob> listMerchantOperations(@RequestParam(defaultValue = "50") int limit) {
    return recoveries.merchantOperations(limit);
  }

  @GetMapping("/merchant-operations/{commandId}")
  Review merchantOperation(@PathVariable UUID commandId) {
    return recoveries.merchantOperation(commandId.toString());
  }

  @PostMapping("/merchant-operations/{commandId}/retry")
  ResponseEntity<MerchantFulfillmentRecovery> retryMerchantOperation(@PathVariable UUID commandId,
                                                                    @Valid @RequestBody MerchantRetryRequest request,
                                                                    Authentication authentication) {
    MerchantFulfillmentRecovery recovery = recoveries.retryMerchantOperation(commandId.toString(),
      new Retry(request.recoveryId().toString(), users.require(authentication).getId(), request.reason(),
        request.evidence()));
    return ResponseEntity.accepted().body(recovery);
  }

  public record MerchantRetryRequest(@NotNull UUID recoveryId, @NotBlank @Size(max = 300) String reason,
                                     @NotBlank @Size(max = 500) String evidence) {
  }
}
