package vip.fairy.commerceflow.commerce.identity.api;

import vip.fairy.commerceflow.commerce.identity.application.UserQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

  private final UserQueryService users;

  public UserController(UserQueryService users) {
    this.users = users;
  }

  @GetMapping
  List<UserSummaryResponse> list() {
    return users.list();
  }
}
