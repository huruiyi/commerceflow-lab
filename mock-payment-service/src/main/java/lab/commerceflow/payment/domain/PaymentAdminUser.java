package lab.commerceflow.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("payment_admin_user")
public class PaymentAdminUser {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String username;
  private String passwordHash;
  private String displayName;
  private String email;
  private String mobile;
  private String status;
  private boolean accountLocked;
  private int loginFailureCount;
  private LocalDateTime lockedUntil;
  private boolean passwordChangeRequired;
  private boolean mfaRequired;
  private boolean mfaEnabled;
  private LocalDateTime lastLoginAt;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getMobile() { return mobile; }
  public void setMobile(String mobile) { this.mobile = mobile; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public boolean isAccountLocked() { return accountLocked; }
  public void setAccountLocked(boolean accountLocked) { this.accountLocked = accountLocked; }
  public int getLoginFailureCount() { return loginFailureCount; }
  public void setLoginFailureCount(int loginFailureCount) { this.loginFailureCount = loginFailureCount; }
  public LocalDateTime getLockedUntil() { return lockedUntil; }
  public void setLockedUntil(LocalDateTime lockedUntil) { this.lockedUntil = lockedUntil; }
  public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
  public void setPasswordChangeRequired(boolean passwordChangeRequired) { this.passwordChangeRequired = passwordChangeRequired; }
  public boolean isMfaRequired() { return mfaRequired; }
  public void setMfaRequired(boolean mfaRequired) { this.mfaRequired = mfaRequired; }
  public boolean isMfaEnabled() { return mfaEnabled; }
  public void setMfaEnabled(boolean mfaEnabled) { this.mfaEnabled = mfaEnabled; }
  public LocalDateTime getLastLoginAt() { return lastLoginAt; }
  public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
  public Long getCreatedBy() { return createdBy; }
  public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
