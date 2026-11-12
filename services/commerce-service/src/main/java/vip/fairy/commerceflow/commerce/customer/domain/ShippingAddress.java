package vip.fairy.commerceflow.commerce.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 用户收货地址持久化实体，用于下单时生成地址快照。
 */
@TableName("shipping_addresses")
public class ShippingAddress {
  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId(type = IdType.AUTO)
  private Long id;
  /**
   * 所属商城用户的内部主键。
   */
  private Long userId;
  /**
   * 收件人姓名。
   */
  private String recipientName;
  /**
   * 收货或账号联系电话。
   */
  private String phone;
  /**
   * ISO 3166-1 国家或地区代码。
   */
  private String countryCode;
  /**
   * 省、州或直辖市名称。
   */
  private String stateProvince;
  /**
   * 城市名称。
   */
  private String city;
  /**
   * 区县或街道所属区域。
   */
  private String district;
  /**
   * 必填的主地址行，包含街道、门牌等。
   */
  private String addressLine1;
  /**
   * 可选的地址补充，如楼层、单元或房号。
   */
  private String addressLine2;
  /**
   * 邮政编码。
   */
  private String postalCode;
  /**
   * 用户自定义地址标签，如“家”或“公司”。
   */
  private String label;
  /**
   * 是否为用户默认收货地址。
   */
  private boolean isDefault;
  /**
   * 记录创建时间。
   */
  private Instant createdAt;
  /**
   * 记录最后更新时间。
   */
  private Instant updatedAt;

  public ShippingAddress() {
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public String getRecipientName() {
    return recipientName;
  }

  public String getPhone() {
    return phone;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public String getStateProvince() {
    return stateProvince;
  }

  public String getCity() {
    return city;
  }

  public String getDistrict() {
    return district;
  }

  public String getAddressLine1() {
    return addressLine1;
  }

  public String getAddressLine2() {
    return addressLine2;
  }

  public String getPostalCode() {
    return postalCode;
  }

  public String getLabel() {
    return label;
  }

  public boolean isDefault() {
    return isDefault;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public void setRecipientName(String value) {
    recipientName = value;
  }

  public void setPhone(String value) {
    phone = value;
  }

  public void setCountryCode(String value) {
    countryCode = value;
  }

  public void setStateProvince(String value) {
    stateProvince = value;
  }

  public void setCity(String value) {
    city = value;
  }

  public void setDistrict(String value) {
    district = value;
  }

  public void setAddressLine1(String value) {
    addressLine1 = value;
  }

  public void setAddressLine2(String value) {
    addressLine2 = value;
  }

  public void setPostalCode(String value) {
    postalCode = value;
  }

  public void setLabel(String value) {
    label = value;
  }

  public void setDefault(boolean value) {
    isDefault = value;
  }

  public void setCreatedAt(Instant value) {
    createdAt = value;
  }

  public void setUpdatedAt(Instant value) {
    updatedAt = value;
  }
}
