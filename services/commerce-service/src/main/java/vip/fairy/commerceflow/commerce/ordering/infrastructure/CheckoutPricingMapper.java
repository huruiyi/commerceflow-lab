package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import vip.fairy.commerceflow.commerce.ordering.application.ShippingTemplateService.Rate;

@Mapper
public interface CheckoutPricingMapper {

  Long lockShippingMerchant(Long merchantId);

  List<Rate> rates(Long merchantId);

  BigDecimal fee(Long merchantId, String countryCode, String stateProvince);

  void saveDefault(Long merchantId, BigDecimal fee);

  void deleteRegions(Long merchantId);

  void insertRegions(Long merchantId, List<Rate> rates);

  int updateRegion(Long merchantId, String countryCode, String stateProvince, Rate rate);

  int setRegionEnabled(Long merchantId, String countryCode, String stateProvince, boolean enabled);

  int deleteRegion(Long merchantId, String countryCode, String stateProvince);

  Receipt receipt(Long customerId, String quoteId);

  void saveReceipt(Long customerId, String quoteId, String scopeHash, Long orderId);

  record Receipt(String scopeHash, Long orderId) {
  }
}
