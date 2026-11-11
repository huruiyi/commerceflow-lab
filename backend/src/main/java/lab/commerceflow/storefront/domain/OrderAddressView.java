package lab.commerceflow.storefront.domain;

public class OrderAddressView {
  private String recipientName, phone, countryCode, stateProvince, city, district, addressLine1, addressLine2, postalCode, label;
  public String getRecipientName(){return recipientName;} public String getPhone(){return phone;} public String getCountryCode(){return countryCode;}
  public String getStateProvince(){return stateProvince;} public String getCity(){return city;} public String getDistrict(){return district;}
  public String getAddressLine1(){return addressLine1;} public String getAddressLine2(){return addressLine2;}
  public String getPostalCode(){return postalCode;} public String getLabel(){return label;}
}
