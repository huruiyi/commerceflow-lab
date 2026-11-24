package vip.fairy.commerceflow.commerce.catalog.api.v1;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import vip.fairy.commerceflow.commerce.catalog.domain.ProductLifecycleStatus;

public record CatalogSkuV1(long skuId, long productId, String code, Map<String, String> specifications,
                           boolean defaultSku, BigDecimal unitPrice, ProductLifecycleStatus status,
                           long businessVersion, Long stockProductId) {

  public record Create(
    @NotNull UUID requestId,
    @NotBlank @Size(max = 64) String code,
    @NotNull @Size(min = 1, max = 4)
    Map<@NotBlank @Size(max = 32) String, @NotBlank @Size(max = 48) String> specifications,
    @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
    @NotNull ProductLifecycleStatus status
  ) {
  }

  public record Update(
    @Positive long expectedVersion,
    @NotBlank @Size(max = 64) String code,
    @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
    @NotNull ProductLifecycleStatus status
  ) {
  }
}
