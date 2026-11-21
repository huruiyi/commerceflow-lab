package vip.fairy.commerceflow.commerce.identity.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.identity.application.contract.v1.InvoiceAccessV1;
import vip.fairy.commerceflow.commerce.identity.infrastructure.AppUserMapper;
import vip.fairy.commerceflow.commerce.identity.infrastructure.PaymentReportGrantMapper;

@Service
public class InvoiceAccessService implements InvoiceAccessV1 {

  private final AppUserMapper users;
  private final PaymentReportGrantMapper grants;
  private final boolean enabled;

  public InvoiceAccessService(AppUserMapper users, PaymentReportGrantMapper grants,
                              @Value("${commerceflow.invoice.enabled:false}") boolean enabled) {
    this.users = users;
    this.grants = grants;
    this.enabled = enabled;
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  public boolean canOperate(long accountId) {
    if (!enabled) {
      return false;
    }
    var user = users.selectById(accountId);
    if (user == null || !user.isEnabled()) {
      return false;
    }
    var roles = users.selectRoleCodes(accountId);
    return roles.contains("ADMIN") && roles.contains("INVOICE_OPERATIONS");
  }

  @Override
  public long requireMerchant(long accountId) {
    Long merchant = grants.ownMerchant(accountId);
    if (merchant == null) {
      throw new AccessDeniedException("An authorized merchant store is required");
    }
    return merchant;
  }

  @Override
  public void requireOperator(long accountId) {
    if (!canOperate(accountId)) {
      throw new AccessDeniedException("Invoice operations authority is required");
    }
  }
}
