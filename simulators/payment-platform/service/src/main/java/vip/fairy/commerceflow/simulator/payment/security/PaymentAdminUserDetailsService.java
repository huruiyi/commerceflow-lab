package vip.fairy.commerceflow.simulator.payment.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentAdminUser;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminUserMapper;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentAdminRoleMapper;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PaymentAdminUserDetailsService implements UserDetailsService {

  private final PaymentAdminUserMapper users;
  private final PaymentAdminRoleMapper roles;

  public PaymentAdminUserDetailsService(PaymentAdminUserMapper users, PaymentAdminRoleMapper roles) {
    this.users = users;
    this.roles = roles;
  }

  @Override
  public UserDetails loadUserByUsername(String username) {
    PaymentAdminUser user = users.selectOne(new LambdaQueryWrapper<PaymentAdminUser>()
      .eq(PaymentAdminUser::getUsername, normalize(username)));
    if (user == null) {
      throw new UsernameNotFoundException("用户名或密码错误");
    }
    List<SimpleGrantedAuthority> authorities = roles.selectEffectivePermissions(user.getId()).stream()
      .map(SimpleGrantedAuthority::new).toList();
    boolean lockExpired = user.getLockedUntil() != null && user.getLockedUntil().isBefore(LocalDateTime.now());
    return new PaymentAdminPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(),
      user.getDisplayName(), user.getEmail(), "ACTIVE".equals(user.getStatus()),
      !user.isAccountLocked() || lockExpired, user.isPasswordChangeRequired(), user.isMfaRequired(),
      user.isMfaEnabled(), authorities);
  }

  public static String normalize(String username) {
    return username == null ? "" : username.trim().toLowerCase();
  }
}
