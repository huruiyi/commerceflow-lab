package lab.commerceflow.storefront.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

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
