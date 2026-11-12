package vip.fairy.commerceflow.commerce.payment.infrastructure;

import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpHeaders;

public final class PaymentSigning {
  private PaymentSigning() {
  }

  public static Credentials credentials(PaymentChannel c) {
    String n = c.name().toLowerCase().replace('_', '-');
    return new Credentials("commerceflow-" + n, "demo-" + n + "-secret");
  }

  public static void apply(HttpHeaders h, String method, String path, String body, String appId, String secret) {
    try {
      String t = "" + Instant.now().getEpochSecond(), n = UUID.randomUUID().toString().replace("-", ""), r = UUID.randomUUID().toString().replace("-", "");
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
      String canonical = String.join("\n", method, path, t, n, r, hash);
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      h.set("X-App-Id", appId);
      h.set("X-Signature-Algorithm", "HMAC_SHA256");
      h.set("X-Timestamp", t);
      h.set("X-Nonce", n);
      h.set("X-Request-Id", r);
      h.set("X-Body-SHA256", hash);
      h.set("X-Signature", Base64.getEncoder().encodeToString(m.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public record Credentials(String appId, String secret) {
  }
}
