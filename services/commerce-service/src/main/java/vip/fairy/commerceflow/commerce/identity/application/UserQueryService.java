package vip.fairy.commerceflow.commerce.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.identity.api.UserSummaryResponse;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.identity.infrastructure.AppUserMapper;

import java.util.List;

@Service
public class UserQueryService {
  private final AppUserMapper users;

  public UserQueryService(AppUserMapper users) {
    this.users = users;
  }

  public List<UserSummaryResponse> list() {
    return users.selectList(new LambdaQueryWrapper<AppUser>()
        .orderByAsc(AppUser::getFirstName, AppUser::getLastName)).stream()
      .map(UserSummaryResponse::from)
      .toList();
  }
}
