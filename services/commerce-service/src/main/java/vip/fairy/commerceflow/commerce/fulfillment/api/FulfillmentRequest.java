package vip.fairy.commerceflow.commerce.fulfillment.api;

import jakarta.validation.constraints.*;

public record FulfillmentRequest(@NotBlank @Size(max = 100) String carrier,
                                 @NotBlank @Size(max = 120) String trackingNumber) {
}
