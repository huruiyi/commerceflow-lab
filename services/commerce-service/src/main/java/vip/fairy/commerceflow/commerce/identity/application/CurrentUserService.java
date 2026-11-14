package vip.fairy.commerceflow.commerce.identity.application;

import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.commerce.identity.infrastructure.AppUserMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

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
    if (user == null) {
      throw new IllegalStateException("Authenticated user no longer exists");
    }
    return user;
  }

  public Long requireId(Authentication authentication) {
    return require(authentication).getId();
  }
}
