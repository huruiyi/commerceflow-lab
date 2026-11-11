package lab.commerceflow.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("payment_admin_mfa_credential")
public class PaymentAdminMfaCredential {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String mfaType;
  private String secretCipher;
  private boolean enabled;
  private LocalDateTime verifiedAt;
  private LocalDateTime createdAt;
  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getUserId() { return userId; }
  public void setUserId(Long userId) { this.userId = userId; }
  public String getMfaType() { return mfaType; }
  public void setMfaType(String mfaType) { this.mfaType = mfaType; }
  public String getSecretCipher() { return secretCipher; }
  public void setSecretCipher(String secretCipher) { this.secretCipher = secretCipher; }
  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public LocalDateTime getVerifiedAt() { return verifiedAt; }
  public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
