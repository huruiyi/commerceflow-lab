package lab.commerceflow.storefront.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lab.commerceflow.storefront.domain.AppUser;
import lab.commerceflow.storefront.mapper.AppUserMapper;
import lab.commerceflow.storefront.web.dto.UserSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
  private final AppUserMapper users;

  public UserController(AppUserMapper users) {
    this.users = users;
  }

  @GetMapping
  List<UserSummaryResponse> list() {
    return users.selectList(new LambdaQueryWrapper<AppUser>()
            .orderByAsc(AppUser::getFirstName, AppUser::getLastName)).stream()
        .map(UserSummaryResponse::from)
        .toList();
  }
}
