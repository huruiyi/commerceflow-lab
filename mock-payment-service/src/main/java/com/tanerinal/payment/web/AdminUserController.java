package com.tanerinal.payment.web;

import com.tanerinal.payment.security.PaymentAdminPrincipal;
import com.tanerinal.payment.service.PaymentAdminAccountService;
import com.tanerinal.payment.service.MfaService;
import com.tanerinal.payment.web.dto.AdminUserResponse;
import com.tanerinal.payment.web.dto.CreateAdminUserRequest;
import com.tanerinal.payment.web.dto.MerchantAccessRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAuthority('ADMIN_USER_MANAGE')")
public class AdminUserController {
  private final PaymentAdminAccountService accounts;
  private final MfaService mfa;

  public AdminUserController(PaymentAdminAccountService accounts, MfaService mfa) {
    this.accounts = accounts;
    this.mfa = mfa;
  }

  @GetMapping
  public List<AdminUserResponse> list() {
    return accounts.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AdminUserResponse create(@Valid @RequestBody CreateAdminUserRequest request,
      @RequestHeader("X-Step-Up-Token") String stepUpToken,
      @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest servletRequest) {
    mfa.consumeStepUp(actor, stepUpToken);
    return accounts.create(request, actor, servletRequest);
  }

  @PostMapping("/{userId}/merchants")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void grantMerchant(@org.springframework.web.bind.annotation.PathVariable long userId,
      @Valid @RequestBody MerchantAccessRequest request,
      @RequestHeader("X-Step-Up-Token") String stepUpToken,
      @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest servletRequest) {
    mfa.consumeStepUp(actor, stepUpToken);
    accounts.grantMerchant(userId, request, actor, servletRequest);
  }
}
