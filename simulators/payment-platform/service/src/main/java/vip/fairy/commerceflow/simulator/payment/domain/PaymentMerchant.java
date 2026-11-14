package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 接入模拟支付平台的商户实体，保存签名凭据和回调配置。
 */
@TableName("payment_merchant")
public class PaymentMerchant {

  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId(type = IdType.AUTO)
  private Long id;
  /**
   * 支付平台分配的商户编号。
   */
  private String merchantNo;
  /**
   * 界面展示名称。
   */
  private String displayName;
  /**
   * 商户调用支付 API 时提交的应用标识。
   */
  private String appId;
  /**
   * 经主密钥加密后的商户或 MFA 秘钥密文。
   */
  private String secretCipher;
  /**
   * 商户密钥末四位，仅用于界面确认，不可用于签名。
   */
  private String secretLastFour;
  /**
   * 商户签名凭据版本，每次轮换递增。
   */
  private int credentialVersion;
  /**
   * 请求签名算法代码。
   */
  private String signingAlgorithm;
  /**
   * 商户上传的非对称签名公钥；HMAC 模式可为空。
   */
  private String merchantPublicKey;
  /**
   * 支付结果异步通知地址。
   */
  private String notifyUrl;
  /**
   * 记录或账号是否可用。
   */
  private boolean enabled;
  /**
   * 创建记录的管理员主键。
   */
  private Long createdBy;
  /**
   * 记录创建时间。
   */
  private LocalDateTime createdAt;
  /**
   * 最后更新记录的管理员主键。
   */
  private Long updatedBy;
  /**
   * 记录最后更新时间。
   */
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getMerchantNo() {
    return merchantNo;
  }

  public void setMerchantNo(String merchantNo) {
    this.merchantNo = merchantNo;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getAppId() {
    return appId;
  }

  public void setAppId(String appId) {
    this.appId = appId;
  }

  public String getSecretCipher() {
    return secretCipher;
  }

  public void setSecretCipher(String secretCipher) {
    this.secretCipher = secretCipher;
  }

  public String getSecretLastFour() {
    return secretLastFour;
  }

  public void setSecretLastFour(String secretLastFour) {
    this.secretLastFour = secretLastFour;
  }

  public int getCredentialVersion() {
    return credentialVersion;
  }

  public void setCredentialVersion(int credentialVersion) {
    this.credentialVersion = credentialVersion;
  }

  public String getSigningAlgorithm() {
    return signingAlgorithm;
  }

  public void setSigningAlgorithm(String signingAlgorithm) {
    this.signingAlgorithm = signingAlgorithm;
  }

  public String getMerchantPublicKey() {
    return merchantPublicKey;
  }

  public void setMerchantPublicKey(String merchantPublicKey) {
    this.merchantPublicKey = merchantPublicKey;
  }

  public String getNotifyUrl() {
    return notifyUrl;
  }

  public void setNotifyUrl(String notifyUrl) {
    this.notifyUrl = notifyUrl;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Long getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(Long createdBy) {
    this.createdBy = createdBy;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public Long getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(Long updatedBy) {
    this.updatedBy = updatedBy;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
