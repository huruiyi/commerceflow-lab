package vip.fairy.commerceflow.commerce.ordering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.StoreTradeV1;
import vip.fairy.commerceflow.commerce.ordering.domain.CouponCampaign;
import vip.fairy.commerceflow.commerce.ordering.domain.CustomerCoupon;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.CouponMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class CouponService {

  private final CouponMapper coupons;
  private final StoreTradeV1 stores;

  public CouponService(CouponMapper coupons, StoreTradeV1 stores) {
    this.coupons = coupons;
    this.stores = stores;
  }

  public List<CouponCampaign> campaigns() {
    var active = stores.activeMerchantIds();
    return coupons.publicCampaigns().stream().filter(campaign -> active.contains(campaign.merchantId())).toList();
  }

  public List<CouponCampaign> campaigns(Long merchant) {
    return coupons.merchantCampaigns(merchant);
  }

  @Transactional
  public CouponCampaign create(Long merchant, String title, BigDecimal minimum, BigDecimal reduction,
                               int supply, Instant start, Instant end) {
    stores.requireActive(merchant, true);
    if (!start.isBefore(end) || !Instant.now().isBefore(end) || minimum.compareTo(reduction) <= 0) {
      throw new IllegalArgumentException("Campaign needs a future expiry and a threshold above its reduction");
    }
    var campaign = new CouponCampaign(UUID.randomUUID().toString(), merchant, title.strip(), minimum,
      reduction, supply, 0, start, end, true);
    coupons.insertCampaign(campaign);
    return campaign;
  }

  public void disable(Long merchant, String id) {
    if (coupons.disableCampaign(id, merchant) != 1) throw new ResourceNotFoundException("Campaign not found");
  }

  @Transactional
  public CustomerCoupon claim(Long customer, String campaignId) {
    var campaign = coupons.findCampaign(campaignId);
    if (campaign == null) throw new ResourceNotFoundException("Campaign not found");
    stores.requireActive(campaign.merchantId(), true);
    if (coupons.lockCampaign(campaignId) == null) throw new ResourceNotFoundException("Campaign not found");
    String existing = coupons.claimed(customer, campaignId);
    if (existing != null) return coupons.coupon(existing, customer);
    if (coupons.issue(campaignId) != 1) throw new BusinessRuleException("Campaign is not available for claiming");
    String id = UUID.randomUUID().toString();
    coupons.insertCoupon(id, campaignId, customer);
    return coupons.coupon(id, customer);
  }

  public List<CustomerCoupon> wallet(Long customer) {
    return coupons.wallet(customer);
  }

  CustomerCoupon price(String id, Long customer, Long merchant, BigDecimal subtotal, boolean lockCampaign) {
    if (id == null) return null;
    CustomerCoupon coupon = coupons.coupon(id, customer);
    if (coupon == null || !merchant.equals(coupon.merchantId())) {
      throw new BusinessRuleException("Coupon is unavailable for these items");
    }
    if (lockCampaign) {
      var campaign = coupons.lockCampaign(coupon.campaignId());
      if (!campaign.enabled()) throw new BusinessRuleException("Coupon campaign is closed");
    }
    Instant now = Instant.now();
    if (!coupon.enabled() || !"AVAILABLE".equals(coupon.status()) || now.isBefore(coupon.startsAt())
        || !now.isBefore(coupon.expiresAt()) || subtotal.compareTo(coupon.minimumAmount()) < 0) {
      throw new BusinessRuleException("Coupon is unavailable or its minimum amount is unmet");
    }
    return coupon;
  }

  void hold(String id, Long customer, String quoteId) {
    if (id != null && coupons.hold(id, customer, quoteId) != 1) {
      throw new BusinessRuleException("Coupon was already locked by another checkout");
    }
  }

  void reconcile(String id, String quoteId, boolean consumed) {
    if (coupons.reconcile(id, quoteId, consumed) != 1) {
      throw new IllegalStateException("Coupon no longer matches its checkout intent");
    }
  }
}
