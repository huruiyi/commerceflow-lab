package vip.fairy.commerceflow.catalog.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@Component
public class InternalRequestSignature {

  public static final String SERVICE_HEADER = "X-CommerceFlow-Service";
  public static final String TIMESTAMP_HEADER = "X-CommerceFlow-Timestamp";
  public static final String REQUEST_ID_HEADER = "X-CommerceFlow-Request-Id";
  public static final String SIGNATURE_HEADER = "X-CommerceFlow-Signature";

  private final String secret;

  public InternalRequestSignature(
    @Value("${commerceflow.internal-api.secret:demo-catalog-internal-secret}") String secret) {
    this.secret = secret;
  }

  public boolean verify(String method, String target, String timestamp, String requestId,
                        String signature) {
    try {
      if (Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > 300) {
        return false;
      }
      return MessageDigest.isEqual(sign(method, target, timestamp, requestId)
          .getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  public String sign(String method, String target, String timestamp, String requestId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String canonical = method + "\n" + target + "\n" + timestamp + "\n" + requestId;
      return java.util.HexFormat.of().formatHex(
        mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign internal catalog request", exception);
    }
  }
}
