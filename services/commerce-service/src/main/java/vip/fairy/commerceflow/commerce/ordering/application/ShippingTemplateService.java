package vip.fairy.commerceflow.commerce.ordering.application;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.CheckoutPricingMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class ShippingTemplateService {

  private static final int MAX_REGIONS = 50;

  private static final Set<String> PROVINCES = Set.of(
    "河北", "山西", "辽宁", "吉林", "黑龙江", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
    "湖北", "湖南", "广东", "海南", "四川", "贵州", "云南", "陕西", "甘肃", "青海", "台湾"
  );
  private static final Map<String, String> OTHER_PROVINCE_NAMES = Map.ofEntries(
    Map.entry("北京", "北京市"), Map.entry("天津", "天津市"), Map.entry("上海", "上海市"), Map.entry("重庆", "重庆市"),
    Map.entry("内蒙古", "内蒙古自治区"), Map.entry("广西", "广西壮族自治区"), Map.entry("西藏", "西藏自治区"),
    Map.entry("宁夏", "宁夏回族自治区"), Map.entry("新疆", "新疆维吾尔自治区"),
    Map.entry("香港", "香港特别行政区"), Map.entry("澳门", "澳门特别行政区")
  );

  private final CheckoutPricingMapper pricing;

  public ShippingTemplateService(CheckoutPricingMapper pricing) {
    this.pricing = pricing;
  }

  public Template get(Long merchantId) {
    var rates = pricing.rates(merchantId);
    return new Template(rates.stream().filter(rate -> rate.countryCode().isEmpty()).findFirst()
      .map(Rate::fee).orElse(BigDecimal.ZERO),
      rates.stream().filter(rate -> !rate.countryCode().isEmpty()).toList());
  }

  @Transactional
  public Template replace(Long merchantId, Template template) {
    pricing.lockShippingMerchant(merchantId);
    Map<List<String>, Boolean> existing = new HashMap<>();
    for (Rate rate : get(merchantId).regions()) {
      existing.put(List.of(rate.countryCode(), rate.stateProvince()), rate.enabled());
    }
    Set<List<String>> regions = new HashSet<>();
    List<Rate> rates = template.regions().stream().map(rate -> {
      Rate normalized = normalizeRate(rate);
      List<String> key = List.of(normalized.countryCode(), normalized.stateProvince());
      if (!regions.add(key)) {
        throw new IllegalArgumentException("Use distinct country and province rules");
      }
      boolean enabled = rate.enabled() == null ? existing.getOrDefault(key, true) : rate.enabled();
      return new Rate(normalized.countryCode(), normalized.stateProvince(), normalized.fee(), enabled);
    }).toList();
    pricing.saveDefault(merchantId, template.defaultFee());
    pricing.deleteRegions(merchantId);
    if (!rates.isEmpty()) {
      pricing.insertRegions(merchantId, rates);
    }
    return new Template(template.defaultFee(), rates);
  }

  @Transactional
  public Template updateDefault(Long merchantId, BigDecimal fee) {
    pricing.lockShippingMerchant(merchantId);
    pricing.saveDefault(merchantId, fee);
    return get(merchantId);
  }

  @Transactional
  public Template addRegion(Long merchantId, Rate rate) {
    pricing.lockShippingMerchant(merchantId);
    if (get(merchantId).regions().size() >= MAX_REGIONS) {
      throw new BusinessRuleException("The shipping template has reached the regional rate limit");
    }
    Rate normalized = normalizeRate(rate);
    try {
      pricing.insertRegions(merchantId,
        List.of(new Rate(normalized.countryCode(), normalized.stateProvince(), normalized.fee(), true)));
    } catch (DuplicateKeyException conflict) {
      throw new BusinessRuleException("A shipping rate already exists for this country and province");
    }
    return get(merchantId);
  }

  @Transactional
  public Template updateRegion(Long merchantId, String country, String province, Rate replacement) {
    pricing.lockShippingMerchant(merchantId);
    RegionKey key = regionKey(country, province);
    try {
      Rate rate = normalizeRate(replacement);
      requireRegion(pricing.updateRegion(merchantId, key.countryCode(), key.stateProvince(), rate));
    } catch (DuplicateKeyException conflict) {
      throw new BusinessRuleException("A shipping rate already exists for this country and province");
    }
    return get(merchantId);
  }

  @Transactional
  public Template setRegionEnabled(Long merchantId, String country, String province, boolean enabled) {
    pricing.lockShippingMerchant(merchantId);
    RegionKey key = regionKey(country, province);
    requireRegion(pricing.setRegionEnabled(merchantId, key.countryCode(), key.stateProvince(), enabled));
    return get(merchantId);
  }

  @Transactional
  public Template deleteRegion(Long merchantId, String country, String province) {
    pricing.lockShippingMerchant(merchantId);
    RegionKey key = regionKey(country, province);
    requireRegion(pricing.deleteRegion(merchantId, key.countryCode(), key.stateProvince()));
    return get(merchantId);
  }

  public BigDecimal fee(Long merchantId, String countryCode, String province) {
    String country = normalize(countryCode);
    BigDecimal fee = pricing.fee(merchantId, country, provinceName(country, province));
    return fee == null ? BigDecimal.ZERO : fee;
  }

  private String provinceName(String country, String province) {
    String name = normalize(province);
    if (!"CN".equals(country)) return name;
    return PROVINCES.contains(name) ? name + "省" : OTHER_PROVINCE_NAMES.getOrDefault(name, name);
  }

  private String normalize(String value) {
    return value.strip().toUpperCase(Locale.ROOT);
  }

  private Rate normalizeRate(Rate rate) {
    RegionKey key = regionKey(rate.countryCode(), rate.stateProvince());
    return new Rate(key.countryCode(), key.stateProvince(), rate.fee(), rate.enabled());
  }

  private RegionKey regionKey(String countryCode, String stateProvince) {
    String country = normalize(countryCode);
    String province = provinceName(country, stateProvince);
    if (!country.matches("[A-Z]{2}") || province.isEmpty() || province.length() > 100) {
      throw new IllegalArgumentException("Use a two-letter country code and a province or state");
    }
    return new RegionKey(country, province);
  }

  private void requireRegion(int changed) {
    if (changed == 0) {
      throw new ResourceNotFoundException("Shipping rate not found");
    }
  }

  private record RegionKey(String countryCode, String stateProvince) {
  }

  public record Template(
    @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 2) BigDecimal defaultFee,
    @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = MAX_REGIONS)
    List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid Rate> regions
  ) {
  }

  public record Rate(
    /** 目标地址的两位国家代码。 */
    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 2) String countryCode,
    /** 目标地址中的省州名称。 */
    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100) String stateProvince,
    /** 每单运费，单位为人民币元。 */
    @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 2) BigDecimal fee,
    /** 是否参与地区运费匹配。 */
    Boolean enabled
  ) {
  }
}
