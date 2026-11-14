package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminUser;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminUserMapper;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminPrincipal;
import vip.fairy.commerceflow.simulator.payment.security.PaymentAdminUserDetailsService;
import vip.fairy.commerceflow.simulator.payment.web.dto.AdminUserResponse;
import vip.fairy.commerceflow.simulator.payment.web.dto.CreateAdminUserRequest;
import vip.fairy.commerceflow.simulator.payment.web.dto.MerchantAccessRequest;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class PaymentAdminAccountService {

  private static final Set<String> ALLOWED_ROLES = Set.of("PAYMENT_ADMIN", "PAYMENT_OPERATOR", "PAYMENT_AUDITOR");
  private final PaymentAdminUserMapper users;
  private final PasswordEncoder passwordEncoder;
  private final AuditService audit;

  public PaymentAdminAccountService(PaymentAdminUserMapper users, PasswordEncoder passwordEncoder, AuditService audit) {
    this.users = users;
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
    if (!ALLOWED_ROLES.containsAll(request.roleCodes())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "包含未知的支付后台角色");
    }
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
    request.roleCodes().forEach(role -> users.insertRole(user.getId(), role));
    audit.record(actor, "CREATE_ADMIN_USER", "ADMIN_USER", user.getId(), "SUCCESS",
      "创建账号并分配角色：" + String.join(",", request.roleCodes()), servletRequest);
    return response(user);
  }

  @Transactional
  public void grantMerchant(long userId, MerchantAccessRequest request, PaymentAdminPrincipal actor,
                            HttpServletRequest servletRequest) {
    if (!Set.of("READ_ONLY", "OPERATE", "REFUND", "AUDIT", "ADMIN").contains(request.permissionScope())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的商户权限范围");
    }
    if (users.selectById(userId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "后台用户不存在");
    }
    users.deleteMerchantAccess(userId, request.merchantId());
    users.insertMerchantAccess(userId, request.merchantId(), request.permissionScope(), actor.getUserId());
    audit.record(actor, "GRANT_MERCHANT_ACCESS", "ADMIN_USER", userId, "SUCCESS",
      "商户 " + request.merchantId() + " 权限：" + request.permissionScope(), servletRequest);
  }

  private AdminUserResponse response(PaymentAdminUser user) {
    return new AdminUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
      user.getMobile(), user.getStatus(), user.isAccountLocked(), user.getLockedUntil(),
      user.isPasswordChangeRequired(), user.isMfaRequired(), user.isMfaEnabled(),
      users.selectRoleCodes(user.getId()), user.getCreatedBy(), user.getCreatedAt(), user.getLastLoginAt());
  }
}
