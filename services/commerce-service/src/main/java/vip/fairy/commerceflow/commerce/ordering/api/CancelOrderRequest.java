package vip.fairy.commerceflow.commerce.ordering.api;

import jakarta.validation.constraints.Size;

public record CancelOrderRequest(@Size(max = 300) String reason) {
}
