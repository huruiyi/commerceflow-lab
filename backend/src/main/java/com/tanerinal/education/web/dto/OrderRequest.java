package com.tanerinal.education.web.dto;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

public class OrderRequest {
  @NotNull
  private Long productId;

  @Size(max = 500)
  private String note;

  @Min(1)
  @Max(99)
  private Integer quantity;

  public Long getProductId() { return productId; }
  public void setProductId(Long productId) { this.productId = productId; }
  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }
  public Integer getQuantity() { return quantity; }
  public void setQuantity(Integer quantity) { this.quantity = quantity; }
}
