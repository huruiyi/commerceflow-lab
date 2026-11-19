package vip.fairy.commerceflow.commerce.identity.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vip.fairy.commerceflow.commerce.identity.application.MerchantAccountService;
import vip.fairy.commerceflow.commerce.identity.application.UserQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

  private final UserQueryService users;
  private final MerchantAccountService merchants;

  public UserController(UserQueryService users, MerchantAccountService merchants) {
    this.users = users;
    this.merchants = merchants;
  }

  @GetMapping
  List<UserSummaryResponse> list() {
    return users.list();
  }

  @PostMapping("/merchants")
  UserSummaryResponse createMerchant(@Valid @RequestBody MerchantAccount account) {
    return merchants.create(account.email(), account.password(), account.name());
  }

  record MerchantAccount(@NotBlank @Email @Size(max = 160) String email,
                         @NotBlank @Size(min = 10, max = 72) String password,
                         @NotBlank @Size(max = 60) String name) {
  }
}
