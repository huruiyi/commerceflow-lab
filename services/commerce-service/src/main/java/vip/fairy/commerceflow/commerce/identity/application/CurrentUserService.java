package vip.fairy.commerceflow.commerce.identity.application;

import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.commerce.identity.infrastructure.AppUserMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@Service
public class CurrentUserService implements CurrentActorIdResolver {

  private final AppUserMapper users;

  public CurrentUserService(AppUserMapper users) {
    this.users = users;
  }

  public AppUser require(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new IllegalStateException("Authentication is required");
    }
    AppUser user = users.selectOne(new LambdaQueryWrapper<AppUser>()
      .eq(AppUser::getEmail, authentication.getName().toLowerCase()));
    if (user == null || !user.isEnabled()) {
      throw new AccessDeniedException("The current account is unavailable");
    }
    return user;
  }

  public boolean hasCurrentRole(Authentication authentication, String role) {
    if (authentication == null || !authentication.isAuthenticated()
        || authentication.getAuthorities().stream().noneMatch(value -> ("ROLE_" + role).equals(value.getAuthority()))) {
      return false;
    }
    return users.selectRoleCodes(require(authentication).getId()).contains(role);
  }

  @Override
  public Long requireId(Authentication authentication) {
    return require(authentication).getId();
  }
}
