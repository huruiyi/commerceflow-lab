package lab.commerceflow.storefront.web.dto;

import jakarta.validation.constraints.Size;

public record CancelOrderRequest(@Size(max = 300) String reason) {}
