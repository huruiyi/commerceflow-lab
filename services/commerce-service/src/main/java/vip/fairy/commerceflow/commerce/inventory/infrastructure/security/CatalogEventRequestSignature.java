package vip.fairy.commerceflow.commerce.inventory.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class CatalogEventRequestSignature {

  private final String secret;

  public CatalogEventRequestSignature(
    @Value("${commerceflow.catalog.event-secret:demo-catalog-internal-secret}") String secret) {
    this.secret = secret;
  }

  public boolean verify(String method, String target, String timestamp, String requestId,
                        String contentHash, String signature) {
    try {
      if (Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > 300) {
        return false;
      }
      return MessageDigest.isEqual(sign(method, target, timestamp, requestId, contentHash)
          .getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  public String sign(String method, String target, String timestamp, String requestId,
                     String contentHash) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String canonical = method + "\n" + target + "\n" + timestamp + "\n" + requestId + "\n"
        + contentHash;
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign catalog event request", exception);
    }
  }

}
