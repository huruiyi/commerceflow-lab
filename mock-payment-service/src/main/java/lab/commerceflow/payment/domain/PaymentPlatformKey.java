package lab.commerceflow.payment.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("payment_platform_key")
public class PaymentPlatformKey {
  @TableId
  private Long id;
  private int keyVersion;
  private String publicKey;
  private String privateKeyCipher;
  private boolean active;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public int getKeyVersion() { return keyVersion; }
  public void setKeyVersion(int value) { this.keyVersion = value; }
  public String getPublicKey() { return publicKey; }
  public void setPublicKey(String value) { this.publicKey = value; }
  public String getPrivateKeyCipher() { return privateKeyCipher; }
  public void setPrivateKeyCipher(String value) { this.privateKeyCipher = value; }
  public boolean isActive() { return active; }
  public void setActive(boolean value) { this.active = value; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
}
