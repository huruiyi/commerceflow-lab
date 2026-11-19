package vip.fairy.commerceflow.order.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InternalRequestSignature {

  private final String commerceSecret;
  private final String paymentSecret;
  private final String fulfillmentSecret;

  public InternalRequestSignature(
    @Value("${commerceflow.internal-api.commerce-secret}") String commerceSecret,
    @Value("${commerceflow.internal-api.payment-secret}") String paymentSecret,
    @Value("${commerceflow.internal-api.fulfillment-secret:}") String fulfillmentSecret
  ) {
    this.commerceSecret = commerceSecret;
    this.paymentSecret = paymentSecret;
    this.fulfillmentSecret = fulfillmentSecret;
  }

  public boolean verify(String service, String method, String target, String timestamp,
                        String requestId, String contentHash, String signature) {
    try {
      if (Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > 300) {
        return false;
      }
      String secret = switch (service) {
        case "commerce-service" -> commerceSecret;
        case "payment-orchestration-service" -> paymentSecret;
        case "fulfillment-service" -> "POST".equals(method)
          && ("/internal/v1/orders/store/query".equals(target)
            || "/internal/v1/orders/store/mutations".equals(target)
            || "/internal/v1/orders/store/packages/reserve".equals(target)
            || "/internal/v1/orders/store/packages/finish".equals(target))
          ? fulfillmentSecret : null;
        default -> null;
      };
      if (secret == null || secret.isBlank()) return false;
      String expected = sign(secret, method + "\n" + target + "\n" + timestamp + "\n"
        + requestId + "\n" + contentHash);
      return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        signature.getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private String sign(String secret, String canonical) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not verify internal order request", exception);
    }
  }
}
