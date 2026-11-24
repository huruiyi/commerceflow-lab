package vip.fairy.commerceflow.commerce.catalog.api.v1;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 公开显示器型号的固定属性。尺寸为英寸，分辨率为像素，刷新率为 Hz。 */
public record CatalogMonitorProfile(
  @NotBlank @Size(max = 32) String brand,
  @NotNull @DecimalMin("1") @DecimalMax("1000") @Digits(integer = 4, fraction = 1) BigDecimal screenSizeInches,
  @Min(1) @Max(32768) int widthPixels,
  @Min(1) @Max(32768) int heightPixels,
  @NotBlank @Size(max = 24) String panel,
  @Min(1) @Max(1000) int refreshRateHz,
  @NotNull @Size(min = 1, max = 8) List<@NotBlank @Size(max = 24) String> ports
) {

  public record Update(@Positive long expectedVersion, @NotNull @Valid CatalogMonitorProfile profile) {
  }
}
