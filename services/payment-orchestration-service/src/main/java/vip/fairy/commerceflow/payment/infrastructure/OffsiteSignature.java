package vip.fairy.commerceflow.payment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class OffsiteSignature {

  private OffsiteSignature() {
  }

  static String digest(String body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(body)));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Could not hash notification request", failure);
    }
  }

  static String sign(String secret, String method, String path, String time, String nonce, String hash) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(bytes(secret), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(bytes(String.join("\n", method, path, time, nonce, hash))));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Could not sign notification request", failure);
    }
  }

  static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
