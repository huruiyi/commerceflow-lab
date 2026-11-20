package vip.fairy.commerceflow.simulator.payment.web;

import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.service.PaymentAdminAccountService;
import vip.fairy.commerceflow.simulator.payment.service.MfaService;
import vip.fairy.commerceflow.simulator.payment.service.PaymentAdminRoleService;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminRoleMapper.RoleBinding;
import vip.fairy.commerceflow.simulator.payment.web.dto.AdminUserResponse;
import vip.fairy.commerceflow.simulator.payment.web.dto.CreateAdminUserRequest;
import vip.fairy.commerceflow.simulator.payment.web.dto.UpdateAdminUserRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
  private final PaymentAdminRoleService roles;

  public AdminUserController(PaymentAdminAccountService accounts, MfaService mfa, PaymentAdminRoleService roles) {
    this.accounts = accounts;
    this.mfa = mfa;
    this.roles = roles;
  }

  @GetMapping
  public List<AdminUserResponse> list() {
    return accounts.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AdminUserResponse create(@Valid @RequestBody CreateAdminUserRequest request,
                                  @RequestHeader("X-Step-Up-Token") String stepUpToken,
                                  @AuthenticationPrincipal PaymentAdminPrincipal actor,
                                  HttpServletRequest servletRequest) {
    mfa.consumeStepUp(actor, stepUpToken);
    return accounts.create(request, actor, servletRequest);
  }

  @PutMapping("/{userId}")
  public AdminUserResponse update(@PathVariable long userId,
                                  @Valid @RequestBody UpdateAdminUserRequest request,
                                  @RequestHeader("X-Step-Up-Token") String stepUpToken,
                                  @AuthenticationPrincipal PaymentAdminPrincipal actor,
                                  HttpServletRequest servletRequest) {
    mfa.consumeStepUp(actor, stepUpToken);
    return accounts.update(userId, request, actor, servletRequest);
  }

  @GetMapping("/{userId}/role-bindings")
  public List<RoleBinding> roles(@PathVariable long userId) {
    return roles.bindings(userId);
  }

  @PutMapping("/{userId}/role-bindings")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void assignRoles(@PathVariable long userId, @Valid @RequestBody AssignRolesRequest input,
                          @RequestHeader("X-Step-Up-Token") String token,
                          @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest request) {
    mfa.consumeStepUp(actor, token);
    roles.assignBindings(userId, input.roleIds(), input.merchantId(), actor, request);
  }

  @PutMapping("/{userId}/role-bindings/{roleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void saveRole(@PathVariable long userId, @PathVariable long roleId,
                       @RequestParam(required = false) Long merchantId, @RequestParam boolean enabled,
                       @RequestHeader("X-Step-Up-Token") String token,
                       @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest request) {
    mfa.consumeStepUp(actor, token);
    roles.changeBinding(userId, roleId, merchantId, enabled, actor, request);
  }

  @DeleteMapping("/{userId}/role-bindings/{roleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeRole(@PathVariable long userId, @PathVariable long roleId,
                         @RequestParam(required = false) Long merchantId,
                         @RequestHeader("X-Step-Up-Token") String token,
                         @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest request) {
    mfa.consumeStepUp(actor, token);
    roles.changeBinding(userId, roleId, merchantId, null, actor, request);
  }

  public record AssignRolesRequest(@NotEmpty List<@NotNull @Positive Long> roleIds, @Positive Long merchantId) {
  }
}
