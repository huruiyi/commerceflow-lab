package lab.commerceflow.storefront.domain;

public class PaymentMerchantAccount {
  private Long id;
  private PaymentChannel channel;
  private String merchantNo;
  private String appId;
  private String displayName;
  private String signingAlgorithm;
  private String apiSecret;
  private boolean enabled;

  public Long getId() { return id; }
  public PaymentChannel getChannel() { return channel; }
  public String getMerchantNo() { return merchantNo; }
  public String getAppId() { return appId; }
  public String getDisplayName() { return displayName; }
  public String getSigningAlgorithm() { return signingAlgorithm; }
  public String getApiSecret() { return apiSecret; }
  public boolean isEnabled() { return enabled; }
}
