package vip.fairy.commerceflow.messaging;

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
class MessagingAccess {

  private final String adminToken;
  private final String senderSecret;

  MessagingAccess(@Value("${messaging.admin-token}") String adminToken,
                  @Value("${messaging.sender-secret}") String senderSecret) {
    if (adminToken.isBlank() || senderSecret.isBlank() || adminToken.equals(senderSecret)) {
      throw new IllegalArgumentException("Separate messaging credentials are required");
    }
    this.adminToken = adminToken;
    this.senderSecret = senderSecret;
  }

  void admin(HttpServletRequest request) {
    if (!matches("Bearer " + adminToken, request.getHeader("Authorization"))) throw denied();
  }

  void sender(HttpServletRequest request, String body) {
    String time = request.getHeader("X-CommerceFlow-Timestamp");
    String nonce = request.getHeader("X-CommerceFlow-Request-Id");
    try {
      long epoch = Long.parseLong(time);
      long now = Instant.now().getEpochSecond();
      if (epoch < now - 120 || epoch > now + 120 || nonce == null
          || !UUID.fromString(nonce).toString().equals(nonce)) throw denied();
      String hash = digest(body);
      String content = String.join("\n", request.getMethod(), request.getRequestURI(), time, nonce, hash);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(bytes(senderSecret), "HmacSHA256"));
      String signed = HexFormat.of().formatHex(mac.doFinal(bytes(content)));
      if (!matches(hash, request.getHeader("X-CommerceFlow-Content-SHA256"))
          || !matches(signed, request.getHeader("X-CommerceFlow-Signature"))) throw denied();
    } catch (IllegalArgumentException failure) {
      throw denied();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Could not verify messaging request", failure);
    }
  }

  static String digest(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Could not hash messaging request", failure);
    }
  }

  private static boolean matches(String expected, String actual) {
    return actual != null && MessageDigest.isEqual(bytes(expected), bytes(actual));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static ResponseStatusException denied() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }
}
