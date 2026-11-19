package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import vip.fairy.commerceflow.commerce.ordering.domain.CouponCampaign;
import vip.fairy.commerceflow.commerce.ordering.domain.CustomerCoupon;

@Mapper
public interface CouponMapper {
  List<CouponCampaign> publicCampaigns();
  List<CouponCampaign> merchantCampaigns(Long merchantId);
  CouponCampaign lockCampaign(String id);
  CouponCampaign findCampaign(String id);
  void insertCampaign(CouponCampaign campaign);
  int disableCampaign(String id, Long merchantId);
  int issue(String campaignId);
  String claimed(Long customerId, String campaignId);
  void insertCoupon(String id, String campaignId, Long customerId);
  List<CustomerCoupon> wallet(Long customerId);
  CustomerCoupon coupon(String id, Long customerId);
  int hold(String id, Long customerId, String quoteId);
  int reconcile(String id, String quoteId, boolean consumed);
}
