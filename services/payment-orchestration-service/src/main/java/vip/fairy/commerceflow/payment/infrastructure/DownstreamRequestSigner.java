package vip.fairy.commerceflow.payment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class DownstreamRequestSigner {

  private final String orderSecret;
  private final String inventorySecret;

  public DownstreamRequestSigner(
    @Value("${commerceflow.order.remote.secret:demo-order-internal-secret}") String orderSecret,
    @Value("${commerceflow.inventory.remote.secret:demo-inventory-internal-secret}")
    String inventorySecret
  ) {
    this.orderSecret = orderSecret;
    this.inventorySecret = inventorySecret;
  }

  public void signOrder(HttpHeaders headers, String target, String payload) {
    sign(headers, target, payload, orderSecret);
  }

  public void signInventory(HttpHeaders headers, String target, String payload) {
    sign(headers, target, payload, inventorySecret);
  }

  private void sign(HttpHeaders headers, String target, String payload, String secret) {
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String requestId = UUID.randomUUID().toString();
    String contentHash = sha256(payload);
    String canonical = "POST\n" + target + "\n" + timestamp + "\n" + requestId + "\n"
      + contentHash;
    headers.set("X-CommerceFlow-Service", "payment-orchestration-service");
    headers.set("X-CommerceFlow-Timestamp", timestamp);
    headers.set("X-CommerceFlow-Request-Id", requestId);
    headers.set("X-CommerceFlow-Content-SHA256", contentHash);
    headers.set("X-CommerceFlow-Signature", hmac(canonical, secret));
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not hash downstream request", exception);
    }
  }

  private String hmac(String value, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign downstream request", exception);
    }
  }
}
