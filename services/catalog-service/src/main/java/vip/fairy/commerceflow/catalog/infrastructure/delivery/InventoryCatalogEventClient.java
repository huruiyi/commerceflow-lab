package vip.fairy.commerceflow.catalog.infrastructure.delivery;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class InventoryCatalogEventClient {

  public static final String TARGET = "/internal/v1/inventory/catalog-events";

  private final RestClient client;
  private final CatalogEventDeliveryProperties properties;

  public InventoryCatalogEventClient(CatalogEventDeliveryProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    this.client = RestClient.builder().requestFactory(requestFactory).build();
    this.properties = properties;
  }

  public void deliver(String payload) {
    client.post().uri(URI.create(properties.getInventoryBaseUrl() + TARGET))
      .headers(headers -> sign(headers, payload))
      .contentType(MediaType.APPLICATION_JSON)
      .body(payload)
      .retrieve()
      .toBodilessEntity();
  }

  private void sign(HttpHeaders headers, String payload) {
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String requestId = UUID.randomUUID().toString();
    String contentHash = sha256(payload);
    headers.set("X-CommerceFlow-Service", "catalog-service");
    headers.set("X-CommerceFlow-Timestamp", timestamp);
    headers.set("X-CommerceFlow-Request-Id", requestId);
    headers.set("X-CommerceFlow-Content-SHA256", contentHash);
    headers.set("X-CommerceFlow-Signature", signature(timestamp, requestId, contentHash));
  }

  private String signature(String timestamp, String requestId, String contentHash) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"));
      String canonical = "POST\n" + TARGET + "\n" + timestamp + "\n" + requestId + "\n"
        + contentHash;
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign inventory catalog event", exception);
    }
  }

  private String sha256(String payload) {
    try {
      return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
