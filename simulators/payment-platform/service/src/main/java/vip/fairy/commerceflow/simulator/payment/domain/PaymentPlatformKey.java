package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 支付平台签名密钥实体，用于密钥版本轮换与验签。
 */
@TableName("payment_platform_key")
public class PaymentPlatformKey {

  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId
  private Long id;
  /**
   * 平台密钥版本号，用于验签时选择正确公钥。
   */
  private int keyVersion;
  /**
   * 对外公布的平台签名公钥。
   */
  private String publicKey;
  /**
   * 经主密钥加密保存的平台私钥密文。
   */
  private String privateKeyCipher;
  /**
   * 该版本密钥是否用于新签名。
   */
  private boolean active;
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

  public int getKeyVersion() {
    return keyVersion;
  }

  public void setKeyVersion(int value) {
    this.keyVersion = value;
  }

  public String getPublicKey() {
    return publicKey;
  }

  public void setPublicKey(String value) {
    this.publicKey = value;
  }

  public String getPrivateKeyCipher() {
    return privateKeyCipher;
  }

  public void setPrivateKeyCipher(String value) {
    this.privateKeyCipher = value;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean value) {
    this.active = value;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime value) {
    this.createdAt = value;
  }
}
