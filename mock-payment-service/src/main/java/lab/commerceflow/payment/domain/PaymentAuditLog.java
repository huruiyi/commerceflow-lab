package lab.commerceflow.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("payment_admin_audit_log")
public class PaymentAuditLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long actorUserId;
  private String actorUsername;
  private String action;
  private String targetType;
  private String targetId;
  private String result;
  private String detail;
  private String sourceIp;
  private String previousHash;
  private String eventHash;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getActorUserId() { return actorUserId; }
  public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
  public String getActorUsername() { return actorUsername; }
  public void setActorUsername(String actorUsername) { this.actorUsername = actorUsername; }
  public String getAction() { return action; }
  public void setAction(String action) { this.action = action; }
  public String getTargetType() { return targetType; }
  public void setTargetType(String targetType) { this.targetType = targetType; }
  public String getTargetId() { return targetId; }
  public void setTargetId(String targetId) { this.targetId = targetId; }
  public String getResult() { return result; }
  public void setResult(String result) { this.result = result; }
  public String getDetail() { return detail; }
  public void setDetail(String detail) { this.detail = detail; }
  public String getSourceIp() { return sourceIp; }
  public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }
  public String getPreviousHash() { return previousHash; }
  public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }
  public String getEventHash() { return eventHash; }
  public void setEventHash(String eventHash) { this.eventHash = eventHash; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
