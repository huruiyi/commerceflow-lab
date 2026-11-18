package vip.fairy.commerceflow.fulfillment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InternalSignature {

  private final SecretKeySpec key;

  public InternalSignature(@Value("${commerceflow.fulfillment.secret}") String secret) {
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  public void require(HttpServletRequest request, String body) {
    if (!verify(request.getMethod(), request.getRequestURI(), body,
        request.getHeader("X-CommerceFlow-Timestamp"), request.getHeader("X-CommerceFlow-Request-Id"),
        request.getHeader("X-CommerceFlow-Content-SHA256"), request.getHeader("X-CommerceFlow-Signature"),
        Instant.now().getEpochSecond())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid fulfillment signature");
    }
  }

  boolean verify(String method, String path, String body, String timestamp, String requestId, String contentHash,
                 String signature, long now) {
    if (timestamp == null || requestId == null || contentHash == null || signature == null) return false;
    try {
      long sent = Long.parseLong(timestamp);
      if (sent < now - 300 || sent > now + 300) return false;
      UUID.fromString(requestId);
      byte[] bodyHash = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
      if (!MessageDigest.isEqual(bodyHash, HexFormat.of().parseHex(contentHash))) return false;
      String canonical = String.join("\n", method, path, timestamp, requestId, contentHash);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return MessageDigest.isEqual(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
        HexFormat.of().parseHex(signature));
    } catch (IllegalArgumentException exception) {
      return false;
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Cannot verify fulfillment signing algorithm", exception);
    }
  }
}
