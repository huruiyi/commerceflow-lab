package vip.fairy.commerceflow.simulator.invoice;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class InvoiceSignature {
  static final String TIMESTAMP_HEADER = "X-Invoice-Timestamp";
  static final String SIGNATURE_HEADER = "X-Invoice-Signature";
  private static final long MAX_CLOCK_SKEW_SECONDS = 300;
  private static final String ALGORITHM = "HmacSHA256";

  private InvoiceSignature() {
  }

  static boolean matches(String secret, String timestamp, String signature, String method, String path, byte[] body) {
    if (timestamp == null || !timestamp.matches("[0-9]{1,19}") ||
      signature == null || !signature.matches("[0-9a-fA-F]{64}")) {
      return false;
    }
    long seconds;
    try {
      seconds = Long.parseLong(timestamp);
    } catch (NumberFormatException ignored) {
      return false;
    }
    long now = Instant.now().getEpochSecond();
    return seconds >= now - MAX_CLOCK_SKEW_SECONDS && seconds <= now + MAX_CLOCK_SKEW_SECONDS &&
      MessageDigest.isEqual(HexFormat.of().parseHex(signature), digest(secret, timestamp, method, path, body));
  }

  static String sign(String secret, String timestamp, String method, String path, byte[] body) {
    return HexFormat.of().formatHex(digest(secret, timestamp, method, path, body));
  }

  private static byte[] digest(String secret, String timestamp, String method, String path, byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      mac.update((timestamp + "\n" + method + "\n" + path + "\n").getBytes(StandardCharsets.UTF_8));
      return mac.doFinal(body);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Invoice HMAC unavailable", ex);
    }
  }
}
