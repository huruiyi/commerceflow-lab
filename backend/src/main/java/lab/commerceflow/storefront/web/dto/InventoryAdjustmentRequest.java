package lab.commerceflow.storefront.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InventoryAdjustmentRequest(
    @NotNull @Min(-100000) @Max(100000) Integer delta,
    @NotBlank @Size(max = 300) String reason) {}
