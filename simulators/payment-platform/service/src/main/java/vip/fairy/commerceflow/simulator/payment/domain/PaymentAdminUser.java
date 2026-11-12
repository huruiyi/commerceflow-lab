package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 支付平台管理员账号实体，记录安全策略与登录状态。
 */
@TableName("payment_admin_user")
public class PaymentAdminUser {
  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId(type = IdType.AUTO)
  private Long id;
  /**
   * 管理员登录名。
   */
  private String username;
  /**
   * 经密码编码器处理的口令哈希，不保存明文。
   */
  private String passwordHash;
  /**
   * 界面展示名称。
   */
  private String displayName;
  /**
   * 用于登录或联系的邮箱地址。
   */
  private String email;
  /**
   * 管理员手机号。
   */
  private String mobile;
  /**
   * 当前业务状态，取值由所属表的状态机约束。
   */
  private String status;
  /**
   * 账号是否被管理员或安全策略锁定。
   */
  private boolean accountLocked;
  /**
   * 连续登录失败次数，成功登录后清零。
   */
  private int loginFailureCount;
  /**
   * 账号临时锁定的截止时间。
   */
  private LocalDateTime lockedUntil;
  /**
   * 下次登录是否必须修改密码。
   */
  private boolean passwordChangeRequired;
  /**
   * 安全策略是否强制启用多因子认证。
   */
  private boolean mfaRequired;
  /**
   * 账号是否已完成多因子认证绑定。
   */
  private boolean mfaEnabled;
  /**
   * 账号最后一次登录成功时间。
   */
  private LocalDateTime lastLoginAt;
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

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getMobile() {
    return mobile;
  }

  public void setMobile(String mobile) {
    this.mobile = mobile;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public boolean isAccountLocked() {
    return accountLocked;
  }

  public void setAccountLocked(boolean accountLocked) {
    this.accountLocked = accountLocked;
  }

  public int getLoginFailureCount() {
    return loginFailureCount;
  }

  public void setLoginFailureCount(int loginFailureCount) {
    this.loginFailureCount = loginFailureCount;
  }

  public LocalDateTime getLockedUntil() {
    return lockedUntil;
  }

  public void setLockedUntil(LocalDateTime lockedUntil) {
    this.lockedUntil = lockedUntil;
  }

  public boolean isPasswordChangeRequired() {
    return passwordChangeRequired;
  }

  public void setPasswordChangeRequired(boolean passwordChangeRequired) {
    this.passwordChangeRequired = passwordChangeRequired;
  }

  public boolean isMfaRequired() {
    return mfaRequired;
  }

  public void setMfaRequired(boolean mfaRequired) {
    this.mfaRequired = mfaRequired;
  }

  public boolean isMfaEnabled() {
    return mfaEnabled;
  }

  public void setMfaEnabled(boolean mfaEnabled) {
    this.mfaEnabled = mfaEnabled;
  }

  public LocalDateTime getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(LocalDateTime lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
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
