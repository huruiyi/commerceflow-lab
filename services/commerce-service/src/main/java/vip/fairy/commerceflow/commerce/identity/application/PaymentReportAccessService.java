package vip.fairy.commerceflow.commerce.identity.application;

import java.util.List;
import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.identity.application.contract.v1.PaymentReportAccessV1;
import vip.fairy.commerceflow.commerce.identity.infrastructure.PaymentReportGrantMapper;
import vip.fairy.commerceflow.commerce.identity.infrastructure.PaymentReportGrantMapper.PlatformGrant;

@Service
public class PaymentReportAccessService implements PaymentReportAccessV1 {

  private final PaymentReportGrantMapper grants;

  public PaymentReportAccessService(PaymentReportGrantMapper grants) {
    this.grants = grants;
  }

  @Override
  public List<Long> platform(long accountId, Long requestedMerchant) {
    var account = grants.platform(accountId);
    List<Long> allowed = account.stream().anyMatch(PlatformGrant::allMerchants) ? null
      : account.stream().map(PlatformGrant::merchantId).filter(Objects::nonNull).toList();
    return restrict(allowed, requestedMerchant);
  }

  @Override
  public List<Long> merchant(long accountId, Long requestedMerchant) {
    Long ownedMerchant = grants.ownMerchant(accountId);
    return restrict(ownedMerchant == null ? List.of() : List.of(ownedMerchant), requestedMerchant);
  }

  @Override
  public List<MerchantOption> merchantOptions(long accountId, boolean platform) {
    List<Long> allowed = platform ? platform(accountId, null) : merchant(accountId, null);
    return grants.merchantOptions(allowed);
  }

  private List<Long> restrict(List<Long> allowed, Long requestedMerchant) {
    if (allowed != null && (allowed.isEmpty() || requestedMerchant != null && !allowed.contains(requestedMerchant))) {
      throw new AccessDeniedException("Payment report access is outside the authorized merchant scope");
    }
    if (requestedMerchant != null && requestedMerchant <= 0) {
      throw new IllegalArgumentException("Merchant identifier must be positive");
    }
    return requestedMerchant == null ? allowed : List.of(requestedMerchant);
  }
}
