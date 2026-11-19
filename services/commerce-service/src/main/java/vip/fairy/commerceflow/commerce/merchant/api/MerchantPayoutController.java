package vip.fairy.commerceflow.commerce.merchant.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.merchant.application.MerchantPayoutService;
import vip.fairy.commerceflow.commerce.merchant.domain.MerchantPayout;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
public class MerchantPayoutController {

  private final MerchantPayoutService payouts;
  private final CurrentActorIdResolver actors;

  public MerchantPayoutController(MerchantPayoutService payouts, CurrentActorIdResolver actors) {
    this.payouts = payouts;
    this.actors = actors;
  }

  @PostMapping("/api/admin/stores/{merchantId}/statements/{id}/payouts")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  MerchantPayout request(@PathVariable Long merchantId, @PathVariable UUID id, Authentication user,
                          @Valid @RequestBody Attempt input) {
    return payouts.request(merchantId, id.toString(), input.requestId().toString(),
      actors.requireId(user), Boolean.TRUE.equals(input.simulateFailure()));
  }

  @GetMapping("/api/admin/stores/{merchantId}/statements/{id}/payouts")
  @PreAuthorize("hasRole('ADMIN')")
  List<MerchantPayout> adminAttempts(@PathVariable Long merchantId, @PathVariable UUID id) {
    return payouts.attempts(merchantId, id.toString());
  }

  @GetMapping("/api/merchant/settlement/statements/{id}/payouts")
  @PreAuthorize("hasRole('MERCHANT')")
  List<MerchantPayout> ownAttempts(@PathVariable UUID id, Authentication user) {
    return payouts.attempts(actors.requireId(user), id.toString());
  }

  @PostMapping("/api/payout-notifications")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void callback(@RequestBody String raw, @RequestHeader("X-Payout-Signature") String signature) {
    payouts.callback(raw, signature);
  }

  record Attempt(@NotNull UUID requestId, Boolean simulateFailure) {
  }
}
