package vip.fairy.commerceflow.simulator.logistics.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LogisticsCallbackSignature {

  public static final String HEADER = "X-Callback-Signature";

  private final SecretKeySpec key;

  public LogisticsCallbackSignature(@Value("${logistics-platform.callback-secret}") String secret) {
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  public String sign(String body) {
    try {
      Mac mac = Mac.getInstance(key.getAlgorithm());
      mac.init(key);
      return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Logistics callback signature failed", exception);
    }
  }

  public boolean matches(String body, String signature) {
    return signature != null && MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
      signature.getBytes(StandardCharsets.UTF_8));
  }
}
