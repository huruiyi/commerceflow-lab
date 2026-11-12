package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 支付管理员多因子凭据实体，保存加密秘钥与验证状态。
 */
@TableName("payment_admin_mfa_credential")
public class PaymentAdminMfaCredential {
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
   * 多因子凭据类型，当前为 TOTP。
   */
  private String mfaType;
  /**
   * 经主密钥加密后的商户或 MFA 秘钥密文。
   */
  private String secretCipher;
  /**
   * 记录或账号是否可用。
   */
  private boolean enabled;
  /**
   * 多因子凭据首次验证通过时间。
   */
  private LocalDateTime verifiedAt;
  /**
   * 记录创建时间。
   */
  private LocalDateTime createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getMfaType() {
    return mfaType;
  }

  public void setMfaType(String mfaType) {
    this.mfaType = mfaType;
  }

  public String getSecretCipher() {
    return secretCipher;
  }

  public void setSecretCipher(String secretCipher) {
    this.secretCipher = secretCipher;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public LocalDateTime getVerifiedAt() {
    return verifiedAt;
  }

  public void setVerifiedAt(LocalDateTime verifiedAt) {
    this.verifiedAt = verifiedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
