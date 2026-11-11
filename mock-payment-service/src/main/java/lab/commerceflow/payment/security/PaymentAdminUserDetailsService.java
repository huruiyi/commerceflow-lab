package lab.commerceflow.payment.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lab.commerceflow.payment.domain.PaymentAdminUser;
import lab.commerceflow.payment.mapper.PaymentAdminUserMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PaymentAdminUserDetailsService implements UserDetailsService {
  private final PaymentAdminUserMapper users;

  public PaymentAdminUserDetailsService(PaymentAdminUserMapper users) {
    this.users = users;
  }

  @Override
  public UserDetails loadUserByUsername(String username) {
    PaymentAdminUser user = users.selectOne(new LambdaQueryWrapper<PaymentAdminUser>()
        .eq(PaymentAdminUser::getUsername, normalize(username)));
    if (user == null) {
      throw new UsernameNotFoundException("用户名或密码错误");
    }
    List<String> roles = users.selectRoleCodes(user.getId());
    Set<SimpleGrantedAuthority> authorities = new LinkedHashSet<>();
    roles.forEach(role -> {
      authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
      authoritiesFor(role).forEach(value -> authorities.add(new SimpleGrantedAuthority(value)));
    });
    boolean lockExpired = user.getLockedUntil() != null && user.getLockedUntil().isBefore(LocalDateTime.now());
    return new PaymentAdminPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(),
        user.getDisplayName(), user.getEmail(), "ACTIVE".equals(user.getStatus()),
        !user.isAccountLocked() || lockExpired, user.isPasswordChangeRequired(), user.isMfaRequired(),
        user.isMfaEnabled(), authorities);
  }

  private static List<String> authoritiesFor(String role) {
    return switch (role) {
      case "PAYMENT_ADMIN" -> List.of("ADMIN_USER_MANAGE", "MERCHANT_MANAGE", "TRANSACTION_READ",
          "TRANSACTION_CLOSE", "AUDIT_READ");
      case "PAYMENT_OPERATOR" -> List.of("TRANSACTION_READ", "TRANSACTION_CLOSE");
      case "PAYMENT_AUDITOR" -> List.of("TRANSACTION_READ", "AUDIT_READ");
      default -> List.of();
    };
  }

  public static String normalize(String username) {
    return username == null ? "" : username.trim().toLowerCase();
  }
}
