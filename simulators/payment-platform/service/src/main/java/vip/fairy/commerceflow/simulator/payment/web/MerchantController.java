package vip.fairy.commerceflow.simulator.payment.web;

import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.service.MerchantService;
import vip.fairy.commerceflow.simulator.payment.service.PlatformKeyService;
import vip.fairy.commerceflow.simulator.payment.web.dto.CreateMerchantRequest;
import vip.fairy.commerceflow.simulator.payment.web.dto.IssuedCredentialResponse;
import vip.fairy.commerceflow.simulator.payment.web.dto.MerchantResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/merchants")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class MerchantController {

  private final MerchantService merchants;
  private final PlatformKeyService keys;

  public MerchantController(MerchantService merchants, PlatformKeyService keys) {
    this.merchants = merchants;
    this.keys = keys;
  }

  @GetMapping
  public List<MerchantResponse> list(@AuthenticationPrincipal PaymentAdminPrincipal actor,
                                     HttpServletRequest request) {
    return merchants.list(actor, request);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public IssuedCredentialResponse create(@Valid @RequestBody CreateMerchantRequest body,
                                         @AuthenticationPrincipal PaymentAdminPrincipal actor,
                                         HttpServletRequest request) {
    return merchants.create(body, actor, request);
  }

  @PostMapping("/{id}/rotate-secret")
  public IssuedCredentialResponse rotate(@PathVariable long id,
                                         @RequestHeader("X-Step-Up-Token") String stepUpToken,
                                         @AuthenticationPrincipal PaymentAdminPrincipal actor,
                                         HttpServletRequest request) {
    return merchants.rotate(id, stepUpToken, actor, request);
  }

  @PostMapping("/{id}/enabled/{enabled}")
  public MerchantResponse enabled(@PathVariable long id, @PathVariable boolean enabled,
                                  @RequestHeader("X-Step-Up-Token") String stepUpToken,
                                  @AuthenticationPrincipal PaymentAdminPrincipal actor,
                                  HttpServletRequest request) {
    return merchants.setEnabled(id, enabled, stepUpToken, actor, request);
  }

  @GetMapping(value = "/platform-public-key", produces = MediaType.TEXT_PLAIN_VALUE)
  public String platformPublicKey() {
    return keys.publicKey();
  }
}
