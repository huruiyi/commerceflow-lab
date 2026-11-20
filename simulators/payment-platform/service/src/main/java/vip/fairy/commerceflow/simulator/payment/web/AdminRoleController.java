package vip.fairy.commerceflow.simulator.payment.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.service.MfaService;
import vip.fairy.commerceflow.simulator.payment.service.PaymentAdminRoleService;
import vip.fairy.commerceflow.simulator.payment.service.PaymentAdminRoleService.PermissionCatalog;
import vip.fairy.commerceflow.simulator.payment.service.PaymentAdminRoleService.RoleDetail;
import vip.fairy.commerceflow.simulator.payment.service.PaymentAdminRoleService.RoleRequest;
import vip.fairy.commerceflow.simulator.payment.service.PaymentAdminRoleService.RoleSummary;

@RestController
@RequestMapping("/api/admin/roles")
@PreAuthorize("hasAuthority('ROLE_MANAGE')")
public class AdminRoleController {

  private final PaymentAdminRoleService roles;
  private final MfaService mfa;

  public AdminRoleController(PaymentAdminRoleService roles, MfaService mfa) {
    this.roles = roles;
    this.mfa = mfa;
  }

  @GetMapping
  public List<RoleSummary> list() {
    return roles.list();
  }

  @GetMapping("/options")
  @PreAuthorize("hasAnyAuthority('ADMIN_USER_MANAGE', 'ROLE_MANAGE')")
  public List<RoleSummary> options() {
    return roles.list().stream().filter(RoleSummary::enabled).toList();
  }

  @GetMapping("/permissions")
  public PermissionCatalog permissions() {
    return roles.permissions();
  }

  @GetMapping("/{id}")
  public RoleDetail detail(@PathVariable long id) {
    return roles.detail(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoleSummary create(@Valid @RequestBody RoleRequest input,
                            @RequestHeader("X-Step-Up-Token") String token,
                            @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest request) {
    mfa.consumeStepUp(actor, token);
    return roles.save(null, input, actor, request);
  }

  @PutMapping("/{id}")
  public RoleSummary update(@PathVariable long id, @Valid @RequestBody RoleRequest input,
                            @RequestHeader("X-Step-Up-Token") String token,
                            @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest request) {
    mfa.consumeStepUp(actor, token);
    return roles.save(id, input, actor, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long id, @RequestHeader("X-Step-Up-Token") String token,
                     @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest request) {
    mfa.consumeStepUp(actor, token);
    roles.delete(id, actor, request);
  }

  @PatchMapping("/{id}/status")
  public RoleSummary updateStatus(@PathVariable long id, @Valid @RequestBody RoleStatusRequest input,
                                  @RequestHeader("X-Step-Up-Token") String token,
                                  @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest request) {
    mfa.consumeStepUp(actor, token);
    return roles.setEnabled(id, input.enabled(), actor, request);
  }

  public record RoleStatusRequest(@NotNull Boolean enabled) {
  }
}
