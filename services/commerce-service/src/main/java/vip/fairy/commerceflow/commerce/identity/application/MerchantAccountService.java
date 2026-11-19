package vip.fairy.commerceflow.commerce.identity.application;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.identity.api.UserSummaryResponse;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.identity.infrastructure.AppUserMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@Service
public class MerchantAccountService {

  private final AppUserMapper users;
  private final PasswordEncoder passwords;

  public MerchantAccountService(AppUserMapper users, PasswordEncoder passwords) {
    this.users = users;
    this.passwords = passwords;
  }

  @Transactional
  public UserSummaryResponse create(String email, String password, String name) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new IllegalArgumentException("Password exceeds 72 bytes");
    }
    var account = new AppUser(email.strip().toLowerCase(Locale.ROOT), passwords.encode(password),
      name.strip(), "", "", "MERCHANT", true);
    try {
      users.insert(account);
    } catch (DuplicateKeyException exception) {
      throw new BusinessRuleException("Account email already exists");
    }
    if (users.grantMerchant(account.getId()) != 1) {
      throw new IllegalStateException("Merchant role is unavailable");
    }
    return UserSummaryResponse.from(account);
  }
}
