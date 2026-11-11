package lab.commerceflow.storefront.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("shipping_addresses")
public class ShippingAddress {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String recipientName;
  private String phone;
  private String countryCode;
  private String stateProvince;
  private String city;
  private String district;
  private String addressLine1;
  private String addressLine2;
  private String postalCode;
  private String label;
  private boolean isDefault;
  private Instant createdAt;
  private Instant updatedAt;

  public ShippingAddress() {}
  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getRecipientName() { return recipientName; }
  public String getPhone() { return phone; }
  public String getCountryCode() { return countryCode; }
  public String getStateProvince() { return stateProvince; }
  public String getCity() { return city; }
  public String getDistrict() { return district; }
  public String getAddressLine1() { return addressLine1; }
  public String getAddressLine2() { return addressLine2; }
  public String getPostalCode() { return postalCode; }
  public String getLabel() { return label; }
  public boolean isDefault() { return isDefault; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUserId(Long userId) { this.userId = userId; }
  public void setRecipientName(String value) { recipientName = value; }
  public void setPhone(String value) { phone = value; }
  public void setCountryCode(String value) { countryCode = value; }
  public void setStateProvince(String value) { stateProvince = value; }
  public void setCity(String value) { city = value; }
  public void setDistrict(String value) { district = value; }
  public void setAddressLine1(String value) { addressLine1 = value; }
  public void setAddressLine2(String value) { addressLine2 = value; }
  public void setPostalCode(String value) { postalCode = value; }
  public void setLabel(String value) { label = value; }
  public void setDefault(boolean value) { isDefault = value; }
  public void setCreatedAt(Instant value) { createdAt = value; }
  public void setUpdatedAt(Instant value) { updatedAt = value; }
}
