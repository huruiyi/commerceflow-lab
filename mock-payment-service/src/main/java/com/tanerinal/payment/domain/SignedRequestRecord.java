package com.tanerinal.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("signed_request_record")
public class SignedRequestRecord {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long merchantId;
  private String requestId;
  private String nonce;
  private long requestTimestamp;
  private String bodySha256;
  private String signingAlgorithm;
  private String requestPath;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getMerchantId() { return merchantId; }
  public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
  public String getRequestId() { return requestId; }
  public void setRequestId(String requestId) { this.requestId = requestId; }
  public String getNonce() { return nonce; }
  public void setNonce(String nonce) { this.nonce = nonce; }
  public long getRequestTimestamp() { return requestTimestamp; }
  public void setRequestTimestamp(long requestTimestamp) { this.requestTimestamp = requestTimestamp; }
  public String getBodySha256() { return bodySha256; }
  public void setBodySha256(String bodySha256) { this.bodySha256 = bodySha256; }
  public String getSigningAlgorithm() { return signingAlgorithm; }
  public void setSigningAlgorithm(String signingAlgorithm) { this.signingAlgorithm = signingAlgorithm; }
  public String getRequestPath() { return requestPath; }
  public void setRequestPath(String requestPath) { this.requestPath = requestPath; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
