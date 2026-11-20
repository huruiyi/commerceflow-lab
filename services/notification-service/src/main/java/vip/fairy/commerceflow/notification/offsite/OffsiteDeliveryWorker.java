package vip.fairy.commerceflow.notification.offsite;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class OffsiteDeliveryWorker {

  private static final String PATH = "/api/provider/v1/deliveries";
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final OffsiteMapper store;
  private final ObjectMapper json;
  private final String endpoint;
  private final String secret;

  OffsiteDeliveryWorker(OffsiteMapper store, ObjectMapper json,
      @Value("${commerceflow.offsite.provider-url:http://messaging-simulator-service:8080}") String endpoint,
      @Value("${commerceflow.offsite.provider-secret:demo-messaging-sender-secret}") String secret) {
    this.store = store;
    this.json = json;
    this.endpoint = endpoint;
    this.secret = secret;
  }

  @Scheduled(fixedDelayString = "${commerceflow.offsite.dispatch-delay:2s}")
  void dispatch() {
    for (var delivery : store.due()) {
      if (Thread.currentThread().isInterrupted()) return;
      String lease = UUID.randomUUID().toString();
      if (store.claim(delivery.requestId(), lease, delivery.attempts()) == 1) deliver(delivery, lease);
    }
  }

  private void deliver(OffsiteMapper.Delivery delivery, String lease) {
    Outcome outcome;
    try {
      outcome = send(delivery);
    } catch (IOException | JacksonException failure) {
      outcome = Outcome.retry("Provider unavailable or invalid response", 5);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      outcome = Outcome.retry("Provider request interrupted", 5);
    }
    store.finish(delivery.requestId(), lease, outcome.status(), outcome.providerId(), outcome.error(), outcome.delay());
  }

  private Outcome send(OffsiteMapper.Delivery delivery) throws IOException, InterruptedException {
    if (delivery.attempts() > 0) {
      var query = request("GET", PATH + "/" + delivery.requestId(), "");
      if (query.statusCode() != 404 || delivery.providerId() != null) return outcome(query, delivery);
    }
    var submission = request("POST", PATH, json.writeValueAsString(delivery.command()));
    return outcome(submission, delivery);
  }

  private Outcome outcome(HttpResponse<String> response, OffsiteMapper.Delivery delivery) {
    int status = response.statusCode();
    if (status == 400 || status == 409) return new Outcome("FAILED", null, "Provider HTTP " + status, 0);
    if (status != 200 && status != 202) {
      int delay = status == 429 ? retryAfter(response) : 5;
      return Outcome.retry("Provider HTTP " + status, delay);
    }
    var receipt = json.readTree(response.body());
    String requestId = receipt.path("requestId").asText();
    String providerId = receipt.path("providerId").asText();
    if (!requestId.equals(delivery.requestId()) || !uuid(providerId)
        || delivery.providerId() != null && !providerId.equals(delivery.providerId())) {
      return Outcome.retry("Provider returned unexpected receipt", 5);
    }
    return switch (receipt.path("status").asText()) {
      case "ACCEPTED" -> new Outcome("ACCEPTED", providerId, null, 2);
      case "DELIVERED" -> new Outcome("DELIVERED", providerId, null, 0);
      case "FAILED" -> new Outcome("FAILED", providerId, "Provider reported delivery failure", 0);
      default -> Outcome.retry("Unknown provider receipt status", 5);
    };
  }

  private int retryAfter(HttpResponse<String> response) {
    try {
      return Math.max(1, Math.min(60, Integer.parseInt(response.headers().firstValue("Retry-After").orElse("5"))));
    } catch (NumberFormatException failure) {
      return 5;
    }
  }

  private boolean uuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }

  private HttpResponse<String> request(String method, String path, String body)
      throws IOException, InterruptedException {
    String time = Long.toString(Instant.now().getEpochSecond());
    String nonce = UUID.randomUUID().toString();
    String hash = OffsiteSignature.digest(body);
    var builder = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(3))
      .header("X-CommerceFlow-Timestamp", time)
      .header("X-CommerceFlow-Request-Id", nonce)
      .header("X-CommerceFlow-Content-SHA256", hash)
      .header("X-CommerceFlow-Signature", OffsiteSignature.sign(secret, method, path, time, nonce, hash));
    var request = "GET".equals(method) ? builder.GET().build()
      : builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private record Outcome(String status, String providerId, String error, int delay) {

    static Outcome retry(String error, int delay) {
      return new Outcome("RETRY", null, error, delay);
    }
  }
}
