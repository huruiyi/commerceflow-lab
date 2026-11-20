package vip.fairy.commerceflow.commerce.notification.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OffsiteNotificationClient {

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String endpoint;
  private final String secret;

  public OffsiteNotificationClient(
      @Value("${commerceflow.offsite.url:http://notification-service:8080}") String endpoint,
      @Value("${commerceflow.offsite.secret:demo-offsite-commerce-secret}") String secret) {
    this.endpoint = endpoint;
    this.secret = secret;
  }

  public void send(String payload) {
    String path = "/internal/v1/offsite-notifications";
    String time = Long.toString(Instant.now().getEpochSecond());
    String nonce = UUID.randomUUID().toString();
    String hash = OffsiteSignature.digest(payload);
    var request = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(3))
      .header("Content-Type", "application/json")
      .header("X-CommerceFlow-Timestamp", time)
      .header("X-CommerceFlow-Request-Id", nonce)
      .header("X-CommerceFlow-Content-SHA256", hash)
      .header("X-CommerceFlow-Signature", OffsiteSignature.sign(secret, "POST", path, time, nonce, hash))
      .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
    try {
      int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      if (status != 204) throw new IllegalStateException("Notification intake HTTP " + status);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Notification intake interrupted", failure);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Notification intake unavailable", failure);
    }
  }
}
