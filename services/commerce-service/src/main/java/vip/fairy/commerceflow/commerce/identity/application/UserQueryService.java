package vip.fairy.commerceflow.commerce.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.commerce.shared.api.AdminListPage;
import org.springframework.transaction.annotation.Transactional;
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

  @Transactional(readOnly = true)
  public AdminListPage<UserSummaryResponse> page(String query, int requestedPage, int requestedSize) {
    String term = query.strip();
    int size = AdminListPage.boundedSize(requestedSize);
    long total = users.countForAdmin(term);
    int page = AdminListPage.availablePage(requestedPage, total, size);
    return new AdminListPage<>(users.pageForAdmin(term, size, (long) page * size), total, page, size);
  }
}
