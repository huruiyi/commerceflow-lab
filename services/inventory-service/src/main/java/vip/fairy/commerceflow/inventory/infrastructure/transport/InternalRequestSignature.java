package vip.fairy.commerceflow.inventory.infrastructure.transport;

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
  private final String catalogSecret;
  private final String fulfillmentSecret;

  public InternalRequestSignature(
    @Value("${commerceflow.internal-api.commerce-secret}") String commerceSecret,
    @Value("${commerceflow.internal-api.payment-secret}") String paymentSecret,
    @Value("${commerceflow.internal-api.catalog-secret}") String catalogSecret,
    @Value("${commerceflow.internal-api.fulfillment-secret}") String fulfillmentSecret
  ) {
    this.commerceSecret = commerceSecret;
    this.paymentSecret = paymentSecret;
    this.catalogSecret = catalogSecret;
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
        case "catalog-service" -> catalogSecret;
        case "fulfillment-service" -> fulfillmentSecret;
        default -> null;
      };
      if (secret == null) {
        return false;
      }
      String expected = sign(secret, method, target, timestamp, requestId, contentHash);
      return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        signature.getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private String sign(String secret, String method, String target, String timestamp,
                      String requestId, String contentHash) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String canonical = method + "\n" + target + "\n" + timestamp + "\n" + requestId + "\n"
        + contentHash;
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not verify internal request", exception);
    }
  }
}
