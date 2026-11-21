package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminRole;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminRoleMapper;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminRoleMapper.RoleAccount;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminRoleMapper.RoleBinding;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminUserMapper;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentMerchantMapper;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;

@Service
public class PaymentAdminRoleService {

  private static final String PLATFORM_ADMIN = "PAYMENT_ADMIN";
  private static final List<String> PLATFORM_PERMISSIONS = List.of("AUDIT_READ", "PAYOUT_READ");
  private static final List<String> SYSTEM_PLATFORM_PERMISSIONS = List.of(
    "AUDIT_READ", "PAYOUT_READ", "TRANSACTION_READ", "REFUND_READ");
  private static final List<String> MERCHANT_PERMISSIONS = List.of(
    "TRANSACTION_READ", "TRANSACTION_CLOSE", "REFUND_READ", "REFUND_OPERATE");

  private final PaymentAdminRoleMapper roles;
  private final PaymentAdminUserMapper users;
  private final PaymentMerchantMapper merchants;
  private final AuditService audit;

  public PaymentAdminRoleService(PaymentAdminRoleMapper roles, PaymentAdminUserMapper users,
                                 PaymentMerchantMapper merchants, AuditService audit) {
    this.roles = roles;
    this.users = users;
    this.merchants = merchants;
    this.audit = audit;
  }

  public List<RoleSummary> list() {
    return roles.selectList(new LambdaQueryWrapper<PaymentAdminRole>()
        .orderByDesc(PaymentAdminRole::isSystemRole).orderByAsc(PaymentAdminRole::getRoleName))
      .stream().map(role -> summary(role, roles.selectAccounts(role.getId()))).toList();
  }

  public RoleDetail detail(long id) {
    PaymentAdminRole role = requireRole(roles.selectById(id));
    List<RoleAccount> accounts = roles.selectAccounts(id);
    return new RoleDetail(summary(role, accounts), accounts);
  }

  public PermissionCatalog permissions() {
    return new PermissionCatalog(PLATFORM_PERMISSIONS, SYSTEM_PLATFORM_PERMISSIONS, MERCHANT_PERMISSIONS);
  }

  @Transactional
  public RoleSummary save(Long id, RoleRequest input, PaymentAdminPrincipal actor, HttpServletRequest request) {
    PaymentAdminRole role = id == null ? new PaymentAdminRole() : requireRole(roles.lockRole(id));
    if (id != null && (!role.getRoleCode().equals(input.code()) || !role.getRoleScope().equals(input.scope()))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色编码和授权层级创建后不可修改");
    }
    Set<String> previous = id == null ? Set.of() : Set.copyOf(roles.selectPermissions(id));
    boolean permissionsChanged = !previous.equals(input.permissions());
    if (PLATFORM_ADMIN.equals(role.getRoleCode())) {
      if (!input.enabled() || permissionsChanged) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "支付平台管理员的权限和启用状态受保护");
      }
    } else {
      List<String> allowed = "MERCHANT".equals(input.scope()) ? MERCHANT_PERMISSIONS
        : role.isSystemRole() ? SYSTEM_PLATFORM_PERMISSIONS : PLATFORM_PERMISSIONS;
      if (!allowed.containsAll(input.permissions())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "包含不适用于此角色的权限");
      }
    }
    if (permissionsChanged || role.isEnabled() != input.enabled()) {
      role.setRevision(role.getRevision() + 1);
    }
    role.setRoleCode(input.code());
    role.setRoleScope(input.scope());
    role.setRoleName(input.name().trim());
    role.setDescription(input.description() == null ? "" : input.description().trim());
    role.setEnabled(input.enabled());
    try {
      if (id == null) {
        roles.insert(role);
      } else {
        roles.updateById(role);
      }
    } catch (DuplicateKeyException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "角色编码已存在", exception);
    }
    if (permissionsChanged) {
      if (id != null) {
        roles.deletePermissions(id);
      }
      input.permissions().stream().sorted().forEach(permission -> roles.insertPermission(role.getId(), permission));
    }
    audit.record(actor, id == null ? "CREATE_ROLE" : "UPDATE_ROLE", "PLATFORM_ROLE", role.getId(), "SUCCESS",
      role.getRoleCode() + "，启用：" + role.isEnabled() + "，权限：" +
        String.join(",", input.permissions().stream().sorted().toList()), request);
    return summary(role, roles.selectAccounts(role.getId()));
  }

  @Transactional
  public RoleSummary setEnabled(long id, boolean enabled, PaymentAdminPrincipal actor, HttpServletRequest request) {
    PaymentAdminRole role = requireRole(roles.lockRole(id));
    if (PLATFORM_ADMIN.equals(role.getRoleCode()) && !enabled) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "支付平台管理员角色不可禁用");
    }
    if (role.isEnabled() != enabled) {
      role.setEnabled(enabled);
      role.setRevision(role.getRevision() + 1);
      roles.updateById(role);
      audit.record(actor, "UPDATE_ROLE", "PLATFORM_ROLE", id, "SUCCESS",
        role.getRoleCode() + "，启用：" + enabled, request);
    }
    return summary(role, roles.selectAccounts(id));
  }

  @Transactional
  public void delete(long id, PaymentAdminPrincipal actor, HttpServletRequest request) {
    PaymentAdminRole role = requireCustomRole(id);
    if (!roles.selectAccounts(id).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "角色仍有关联账号，请先移除授权");
    }
    roles.deleteById(id);
    audit.record(actor, "DELETE_ROLE", "PLATFORM_ROLE", id, "SUCCESS", role.getRoleCode(), request);
  }

  public List<RoleBinding> bindings(long userId) {
    if (users.selectById(userId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "后台用户不存在");
    }
    return roles.selectBindings(userId);
  }

  @Transactional
  public void assignBindings(long userId, List<Long> roleIds, Long merchantId,
                             PaymentAdminPrincipal actor, HttpServletRequest request) {
    for (long roleId : roleIds.stream().distinct().sorted().toList()) {
      changeBinding(userId, roleId, merchantId, true, actor, request);
    }
  }

  @Transactional
  public void changeBinding(long userId, long roleId, Long merchantId, Boolean enabled,
                            PaymentAdminPrincipal actor, HttpServletRequest request) {
    if (!users.lockAccountIds(actor.getUserId(), userId).contains(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "后台用户不存在");
    }
    if (!users.selectRoleCodes(actor.getUserId()).contains(PLATFORM_ADMIN)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号没有授权权限");
    }
    PaymentAdminRole role = requireRole(roles.lockRole(roleId));
    if (("MERCHANT".equals(role.getRoleScope())) != (merchantId != null)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色与授权范围不一致");
    }
    if (merchantId != null && merchants.selectById(merchantId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");
    }
    if (Boolean.TRUE.equals(enabled) && !role.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "角色已全局停用");
    }
    if (actor.getUserId() == userId && PLATFORM_ADMIN.equals(role.getRoleCode()) && !Boolean.TRUE.equals(enabled)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用或移除自己的支付管理员角色");
    }
    if (merchantId != null) {
      if (enabled == null) {
        roles.deleteMerchantBinding(userId, merchantId, roleId);
      } else {
        roles.saveMerchantBinding(userId, merchantId, roleId, enabled);
      }
    } else if (enabled == null) {
      roles.deletePlatformBinding(userId, roleId);
    } else {
      roles.savePlatformBinding(userId, roleId, enabled);
    }
    String action = enabled == null ? "REMOVE_USER_ROLE" : enabled ? "ENABLE_USER_ROLE" : "DISABLE_USER_ROLE";
    String scope = merchantId == null ? "平台" : "商户 " + merchantId;
    audit.record(actor, action, "ADMIN_USER", userId, "SUCCESS", scope + " · " + role.getRoleCode(), request);
  }

  public boolean isPlatformAdmin(PaymentAdminPrincipal actor) {
    return actor.getAuthorities().stream().anyMatch(auth -> auth.getAuthority().equals("ROLE_" + PLATFORM_ADMIN));
  }

  public List<Long> merchantIds(PaymentAdminPrincipal actor, String permission) {
    return roles.selectAuthorizedMerchants(actor.getUserId(), permission);
  }

  public void requireMerchant(PaymentAdminPrincipal actor, long merchantId, String permission) {
    if (!isPlatformAdmin(actor) && !merchantIds(actor, permission).contains(merchantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有该商户的操作权限");
    }
  }

  private PaymentAdminRole requireCustomRole(long id) {
    PaymentAdminRole role = requireRole(roles.lockRole(id));
    if (role.isSystemRole()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "内置角色不可删除");
    }
    return role;
  }

  private PaymentAdminRole requireRole(PaymentAdminRole role) {
    if (role == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
    }
    return role;
  }

  private RoleSummary summary(PaymentAdminRole role, List<RoleAccount> accounts) {
    long accountCount = accounts.stream().map(RoleAccount::userId).distinct().count();
    return new RoleSummary(role.getId(), role.getRoleCode(), role.getRoleName(), role.getRoleScope(),
      role.getDescription(), role.isEnabled(), role.isSystemRole(),
      roles.selectPermissions(role.getId()), accountCount);
  }

  public record RoleRequest(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String code,
                            @NotBlank @Size(max = 100) String name,
                            @NotBlank @Pattern(regexp = "PLATFORM|MERCHANT") String scope,
                            @Size(max = 255) String description, boolean enabled,
                            @NotEmpty Set<@NotBlank String> permissions) {
  }

  public record RoleSummary(long id, String code, String name, String scope, String description, boolean enabled,
                            boolean systemRole, List<String> permissions, long accountCount) {
  }

  public record RoleDetail(RoleSummary role, List<RoleAccount> accounts) {
  }

  public record PermissionCatalog(List<String> platform, List<String> systemPlatform, List<String> merchant) {
  }
}
