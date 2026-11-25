package vip.fairy.commerceflow.commerce.ordering.application;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.CheckoutPricingMapper;

@Service
public class ShippingTemplateService {

  private static final Map<String, String> AUTONOMOUS_REGION_NAMES = Map.of(
    "内蒙古自治区", "内蒙古", "广西壮族自治区", "广西", "西藏自治区", "西藏",
    "宁夏回族自治区", "宁夏", "新疆维吾尔自治区", "新疆"
  );
  private static final Set<String> DOMESTIC_PROVINCE_NAMES = Set.of(
    "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏", "浙江", "安徽",
    "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
    "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆", "台湾", "香港", "澳门"
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
    Set<List<String>> regions = new HashSet<>();
    List<Rate> rates = template.regions().stream().map(rate -> {
      String country = normalize(rate.countryCode());
      String province = normalize(rate.stateProvince());
      if (!country.matches("[A-Z]{2}") || province.isEmpty() || province.length() > 100
          || !regions.add(List.of(country, province))) {
        throw new IllegalArgumentException("Use distinct country and province rules");
      }
      return new Rate(country, province, rate.fee());
    }).toList();
    pricing.saveDefault(merchantId, template.defaultFee());
    pricing.deleteRegions(merchantId);
    if (!rates.isEmpty()) {
      pricing.insertRegions(merchantId, rates);
    }
    return new Template(template.defaultFee(), rates);
  }

  public BigDecimal fee(Long merchantId, String countryCode, String province) {
    String country = normalize(countryCode);
    String name = normalize(province);
    if ("CN".equals(country)) {
      return domesticFee(get(merchantId), name);
    }
    BigDecimal fee = pricing.fee(merchantId, country, name);
    return fee == null ? BigDecimal.ZERO : fee;
  }

  private BigDecimal domesticFee(Template template, String name) {
    String provinceKey = domesticProvinceKey(name);
    BigDecimal matchingAlias = template.defaultFee();
    for (Rate rate : template.regions()) {
      if (!"CN".equals(rate.countryCode())) continue;
      if (rate.stateProvince().equals(name)) return rate.fee();
      if (domesticProvinceKey(rate.stateProvince()).equals(provinceKey)) matchingAlias = rate.fee();
    }
    return matchingAlias;
  }

  private String domesticProvinceKey(String name) {
    String shortName = AUTONOMOUS_REGION_NAMES.getOrDefault(name, name.replaceFirst("(省|市|特别行政区)$", ""));
    return DOMESTIC_PROVINCE_NAMES.contains(shortName) ? shortName : name;
  }

  private String normalize(String value) {
    return value.strip().toUpperCase(Locale.ROOT);
  }

  public record Template(
    @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 2) BigDecimal defaultFee,
    @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max = 50)
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
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 2) BigDecimal fee
  ) {
  }
}
