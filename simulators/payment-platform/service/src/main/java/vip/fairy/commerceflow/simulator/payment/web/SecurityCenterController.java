package vip.fairy.commerceflow.simulator.payment.web;

import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.service.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class SecurityCenterController {
  private final MfaService mfa;

  public SecurityCenterController(MfaService mfa) {
    this.mfa = mfa;
  }

  @PostMapping("/mfa/enroll")
  public MfaService.Enrollment enroll(@Valid @RequestBody PasswordRequest body,
                                      @AuthenticationPrincipal PaymentAdminPrincipal actor,
                                      HttpServletRequest request) {
    return mfa.enroll(actor, body.password(), request);
  }

  @PostMapping("/mfa/confirm")
  public MfaService.RecoveryCodes confirm(@Valid @RequestBody CodeRequest body,
                                          @AuthenticationPrincipal PaymentAdminPrincipal actor,
                                          HttpServletRequest request) {
    return mfa.confirm(actor, body.code(), request);
  }

  @PostMapping("/step-up")
  public MfaService.StepUp stepUp(@Valid @RequestBody StepUpRequest body,
                                  @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest request) {
    return mfa.stepUp(actor, body.password(), body.code(), request);
  }

  public record PasswordRequest(@NotBlank String password) {
  }

  public record CodeRequest(@NotBlank String code) {
  }

  public record StepUpRequest(@NotBlank String password, String code) {
  }
}
