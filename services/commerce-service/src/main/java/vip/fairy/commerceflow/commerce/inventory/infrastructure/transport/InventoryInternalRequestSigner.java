package vip.fairy.commerceflow.commerce.inventory.infrastructure.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class InventoryInternalRequestSigner {

  private final String secret;

  public InventoryInternalRequestSigner(
    @Value("${commerceflow.inventory.remote.secret:demo-inventory-internal-secret}") String secret
  ) {
    this.secret = secret;
  }

  public String contentHash(String payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not hash inventory request", exception);
    }
  }

  public void sign(HttpHeaders headers, String method, String target, String contentHash) {
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String requestId = UUID.randomUUID().toString();
    headers.set("X-CommerceFlow-Service", "commerce-service");
    headers.set("X-CommerceFlow-Timestamp", timestamp);
    headers.set("X-CommerceFlow-Request-Id", requestId);
    headers.set("X-CommerceFlow-Content-SHA256", contentHash);
    headers.set("X-CommerceFlow-Signature",
      hmac(method + "\n" + target + "\n" + timestamp + "\n" + requestId + "\n" + contentHash));
  }

  private String hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign inventory request", exception);
    }
  }
}
