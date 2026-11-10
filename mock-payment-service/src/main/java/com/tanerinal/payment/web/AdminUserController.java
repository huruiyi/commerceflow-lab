package com.tanerinal.payment.web;

import com.tanerinal.payment.security.PaymentAdminPrincipal;
import com.tanerinal.payment.service.PaymentAdminAccountService;
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

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAuthority('ADMIN_USER_MANAGE')")
public class AdminUserController {
  private final PaymentAdminAccountService accounts;

  public AdminUserController(PaymentAdminAccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping
  public List<AdminUserResponse> list() {
    return accounts.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AdminUserResponse create(@Valid @RequestBody CreateAdminUserRequest request,
      @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest servletRequest) {
    return accounts.create(request, actor, servletRequest);
  }

  @PostMapping("/{userId}/merchants")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void grantMerchant(@org.springframework.web.bind.annotation.PathVariable long userId,
      @Valid @RequestBody MerchantAccessRequest request,
      @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest servletRequest) {
    accounts.grantMerchant(userId, request, actor, servletRequest);
  }
}
