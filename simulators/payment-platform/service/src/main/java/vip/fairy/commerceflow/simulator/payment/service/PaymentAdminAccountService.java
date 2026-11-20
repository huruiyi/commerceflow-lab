package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminUser;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminRole;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminRoleMapper;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminUserMapper;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminUserDetailsService;
import vip.fairy.commerceflow.simulator.payment.web.dto.AdminUserResponse;
import vip.fairy.commerceflow.simulator.payment.web.dto.CreateAdminUserRequest;
import vip.fairy.commerceflow.simulator.payment.web.dto.UpdateAdminUserRequest;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class PaymentAdminAccountService {

  private final PaymentAdminUserMapper users;
  private final PaymentAdminRoleMapper roles;
  private final PasswordEncoder passwordEncoder;
  private final AuditService audit;

  public PaymentAdminAccountService(PaymentAdminUserMapper users, PaymentAdminRoleMapper roles,
                                    PasswordEncoder passwordEncoder, AuditService audit) {
    this.users = users;
    this.roles = roles;
    this.passwordEncoder = passwordEncoder;
    this.audit = audit;
  }

  public List<AdminUserResponse> list() {
    return users.selectList(new LambdaQueryWrapper<PaymentAdminUser>().orderByAsc(PaymentAdminUser::getId))
      .stream().map(this::response).toList();
  }

  @Transactional
  public AdminUserResponse create(CreateAdminUserRequest request, PaymentAdminPrincipal actor,
                                  HttpServletRequest servletRequest) {
    String username = PaymentAdminUserDetailsService.normalize(request.username());
    String email = request.email().trim().toLowerCase(Locale.ROOT);
    users.lockAccountIds(actor.getUserId(), actor.getUserId());
    requireAccountManager(actor.getUserId());
    Long duplicate = users.selectCount(new LambdaQueryWrapper<PaymentAdminUser>()
      .eq(PaymentAdminUser::getUsername, username).or().eq(PaymentAdminUser::getEmail, email));
    if (duplicate > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名或邮箱已存在");
    }
    PaymentAdminUser user = new PaymentAdminUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setDisplayName(request.displayName().trim());
    user.setEmail(email);
    user.setMobile(request.mobile());
    user.setStatus("ACTIVE");
    user.setPasswordChangeRequired(request.requirePasswordChange());
    user.setMfaRequired(request.requireMfa());
    user.setMfaEnabled(false);
    user.setCreatedBy(actor.getUserId());
    user.setUpdatedBy(actor.getUserId());
    user.setCreatedAt(LocalDateTime.now());
    user.setUpdatedAt(LocalDateTime.now());
    users.insert(user);
    for (String code : request.roleCodes().stream().sorted().toList()) {
      PaymentAdminRole candidate = roles.selectOne(new LambdaQueryWrapper<PaymentAdminRole>()
        .eq(PaymentAdminRole::getRoleCode, code));
      PaymentAdminRole role = candidate == null ? null : roles.lockRole(candidate.getId());
      if (role == null || !role.isEnabled() || !"PLATFORM".equals(role.getRoleScope())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择已启用的平台角色");
      }
      roles.savePlatformBinding(user.getId(), role.getId(), true);
    }
    audit.record(actor, "CREATE_ADMIN_USER", "ADMIN_USER", user.getId(), "SUCCESS",
      "创建账号并分配角色：" + String.join(",", request.roleCodes()), servletRequest);
    return response(user);
  }

  @Transactional
  public AdminUserResponse update(long userId, UpdateAdminUserRequest request, PaymentAdminPrincipal actor,
                                  HttpServletRequest servletRequest) {
    long actorId = actor.getUserId();
    if (!users.lockAccountIds(actorId, userId).contains(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "后台用户不存在");
    }
    requireAccountManager(actorId);

    String email = request.email().trim().toLowerCase(Locale.ROOT);
    try {
      users.update(null, new LambdaUpdateWrapper<PaymentAdminUser>()
        .eq(PaymentAdminUser::getId, userId)
        .set(PaymentAdminUser::getDisplayName, request.displayName().trim())
        .set(PaymentAdminUser::getEmail, email)
        .set(PaymentAdminUser::getUpdatedBy, actorId)
        .set(PaymentAdminUser::getUpdatedAt, LocalDateTime.now()));
    } catch (DuplicateKeyException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "邮箱已被其他账号使用", exception);
    }

    audit.record(actor, "UPDATE_ADMIN_USER", "ADMIN_USER", userId, "SUCCESS", "更新账号资料", servletRequest);
    return response(users.selectById(userId));
  }

  private void requireAccountManager(long userId) {
    if (!users.selectRoleCodes(userId).contains("PAYMENT_ADMIN")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号没有执行此操作的权限");
    }
  }

  private AdminUserResponse response(PaymentAdminUser user) {
    return new AdminUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
      user.getMobile(), user.getStatus(), user.isAccountLocked(), user.getLockedUntil(),
      user.isPasswordChangeRequired(), user.isMfaRequired(), user.isMfaEnabled(),
      users.selectRoleCodes(user.getId()), user.getCreatedBy(), user.getCreatedAt(), user.getLastLoginAt());
  }
}
