package vip.fairy.commerceflow.payment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ChannelCallbackSignature {

  private final String secret;

  public ChannelCallbackSignature(
    @Value("${commerceflow.payment-platform.callback-secret}") String secret
  ) {
    this.secret = secret;
  }

  public boolean valid(String payload, String signature) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return signature != null && MessageDigest.isEqual(
        mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), Base64.getDecoder().decode(signature));
    } catch (Exception exception) {
      return false;
    }
  }
}
