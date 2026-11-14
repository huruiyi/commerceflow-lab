package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 支付管理操作审计实体，使用哈希链检测历史记录篡改。
 */
@TableName("payment_admin_audit_log")
public class PaymentAuditLog {

  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId(type = IdType.AUTO)
  private Long id;
  /**
   * 执行受审计操作的管理员主键。
   */
  private Long actorUserId;
  /**
   * 执行操作时的管理员登录名快照。
   */
  private String actorUsername;
  /**
   * 受审计的操作代码。
   */
  private String action;
  /**
   * 受操作资源的类型代码。
   */
  private String targetType;
  /**
   * 受操作资源的业务或内部标识。
   */
  private String targetId;
  /**
   * 操作结果代码，如 SUCCESS 或 FAILURE。
   */
  private String result;
  /**
   * 便于审计人员阅读的操作摘要。
   */
  private String detail;
  /**
   * 发起操作的客户端 IP 地址。
   */
  private String sourceIp;
  /**
   * 上一条审计事件哈希，用于构成链式校验。
   */
  private String previousHash;
  /**
   * 当前审计事件内容哈希。
   */
  private String eventHash;
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

  public Long getActorUserId() {
    return actorUserId;
  }

  public void setActorUserId(Long actorUserId) {
    this.actorUserId = actorUserId;
  }

  public String getActorUsername() {
    return actorUsername;
  }

  public void setActorUsername(String actorUsername) {
    this.actorUsername = actorUsername;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getTargetType() {
    return targetType;
  }

  public void setTargetType(String targetType) {
    this.targetType = targetType;
  }

  public String getTargetId() {
    return targetId;
  }

  public void setTargetId(String targetId) {
    this.targetId = targetId;
  }

  public String getResult() {
    return result;
  }

  public void setResult(String result) {
    this.result = result;
  }

  public String getDetail() {
    return detail;
  }

  public void setDetail(String detail) {
    this.detail = detail;
  }

  public String getSourceIp() {
    return sourceIp;
  }

  public void setSourceIp(String sourceIp) {
    this.sourceIp = sourceIp;
  }

  public String getPreviousHash() {
    return previousHash;
  }

  public void setPreviousHash(String previousHash) {
    this.previousHash = previousHash;
  }

  public String getEventHash() {
    return eventHash;
  }

  public void setEventHash(String eventHash) {
    this.eventHash = eventHash;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
