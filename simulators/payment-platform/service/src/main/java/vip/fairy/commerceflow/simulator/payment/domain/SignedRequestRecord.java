package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 商户签名请求防重放实体，记录 nonce、时间戳与请求摘要。
 */
@TableName("signed_request_record")
public class SignedRequestRecord {
  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId(type = IdType.AUTO)
  private Long id;
  /**
   * 关联支付商户的内部主键。
   */
  private Long merchantId;
  /**
   * 商户为单次 API 请求分配的唯一标识。
   */
  private String requestId;
  /**
   * 防重放的一次性随机值。
   */
  private String nonce;
  /**
   * 商户请求时间的 Unix 秒级时间戳。
   */
  private long requestTimestamp;
  /**
   * 请求体原始字节的 SHA-256 十六进制摘要。
   */
  private String bodySha256;
  /**
   * 请求签名算法代码。
   */
  private String signingAlgorithm;
  /**
   * 参与签名的 HTTP 请求路径。
   */
  private String requestPath;
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

  public Long getMerchantId() {
    return merchantId;
  }

  public void setMerchantId(Long merchantId) {
    this.merchantId = merchantId;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  public String getNonce() {
    return nonce;
  }

  public void setNonce(String nonce) {
    this.nonce = nonce;
  }

  public long getRequestTimestamp() {
    return requestTimestamp;
  }

  public void setRequestTimestamp(long requestTimestamp) {
    this.requestTimestamp = requestTimestamp;
  }

  public String getBodySha256() {
    return bodySha256;
  }

  public void setBodySha256(String bodySha256) {
    this.bodySha256 = bodySha256;
  }

  public String getSigningAlgorithm() {
    return signingAlgorithm;
  }

  public void setSigningAlgorithm(String signingAlgorithm) {
    this.signingAlgorithm = signingAlgorithm;
  }

  public String getRequestPath() {
    return requestPath;
  }

  public void setRequestPath(String requestPath) {
    this.requestPath = requestPath;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
