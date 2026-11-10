package com.tanerinal.education.web.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

public class QuantityUpdateRequest {
  @NotNull
  @Min(1)
  @Max(99)
  private Integer quantity;

  public Integer getQuantity() { return quantity; }
  public void setQuantity(Integer quantity) { this.quantity = quantity; }
}
