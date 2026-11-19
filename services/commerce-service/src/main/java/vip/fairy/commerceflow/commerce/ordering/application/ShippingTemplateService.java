package vip.fairy.commerceflow.commerce.ordering.application;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.CheckoutPricingMapper;

@Service
public class ShippingTemplateService {

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
    BigDecimal fee = pricing.fee(merchantId, normalize(countryCode), normalize(province));
    return fee == null ? BigDecimal.ZERO : fee;
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
