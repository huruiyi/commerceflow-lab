package com.tanerinal.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("payment_merchant")
public class PaymentMerchant {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String merchantNo;
  private String displayName;
  private String appId;
  private String secretCipher;
  private String secretLastFour;
  private int credentialVersion;
  private String signingAlgorithm;
  private String merchantPublicKey;
  private String notifyUrl;
  private boolean enabled;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getMerchantNo() { return merchantNo; }
  public void setMerchantNo(String merchantNo) { this.merchantNo = merchantNo; }
  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }
  public String getAppId() { return appId; }
  public void setAppId(String appId) { this.appId = appId; }
  public String getSecretCipher() { return secretCipher; }
  public void setSecretCipher(String secretCipher) { this.secretCipher = secretCipher; }
  public String getSecretLastFour() { return secretLastFour; }
  public void setSecretLastFour(String secretLastFour) { this.secretLastFour = secretLastFour; }
  public int getCredentialVersion() { return credentialVersion; }
  public void setCredentialVersion(int credentialVersion) { this.credentialVersion = credentialVersion; }
  public String getSigningAlgorithm() { return signingAlgorithm; }
  public void setSigningAlgorithm(String signingAlgorithm) { this.signingAlgorithm = signingAlgorithm; }
  public String getMerchantPublicKey() { return merchantPublicKey; }
  public void setMerchantPublicKey(String merchantPublicKey) { this.merchantPublicKey = merchantPublicKey; }
  public String getNotifyUrl() { return notifyUrl; }
  public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }
  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public Long getCreatedBy() { return createdBy; }
  public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
