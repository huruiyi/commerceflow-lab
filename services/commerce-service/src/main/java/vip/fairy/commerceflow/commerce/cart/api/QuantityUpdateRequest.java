package vip.fairy.commerceflow.commerce.cart.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class QuantityUpdateRequest {

  @NotNull
  @Min(1)
  @Max(99)
  private Integer quantity;

  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }
}
