package vip.fairy.commerceflow.commerce.ordering.domain;

/**
 * 订单收货地址快照投影，内容在下单时从客户地址复制，后续不随地址簿修改。
 */
public class OrderAddressView {
  /**
   * 收件人姓名。
   */
  private String recipientName;
  /**
   * 收件联系电话。
   */
  private String phone;
  /**
   * ISO 3166-1 alpha-2 国家或地区代码。
   */
  private String countryCode;
  /**
   * 省、州或同级行政区。
   */
  private String stateProvince;
  /**
   * 城市名称。
   */
  private String city;
  /**
   * 区、县或同级行政区。
   */
  private String district;
  /**
   * 主要街道和门牌地址。
   */
  private String addressLine1;
  /**
   * 楼栋、单元等补充地址，可以为空。
   */
  private String addressLine2;
  /**
   * 邮政编码。
   */
  private String postalCode;
  /**
   * 下单时的地址标签，例如“家”或“公司”。
   */
  private String label;

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
}
