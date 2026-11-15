package vip.fairy.commerceflow.commerce.notification.infrastructure.client.notification;

import vip.fairy.commerceflow.commerce.notification.application.NotificationServiceUnavailableException;
import vip.fairy.commerceflow.commerce.notification.domain.CustomerNotification;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

@Component
public class NotificationServiceClient {

  private static final String SERVICE_HEADER = "X-CommerceFlow-Service";
  private static final String TIMESTAMP_HEADER = "X-CommerceFlow-Timestamp";
  private static final String REQUEST_ID_HEADER = "X-CommerceFlow-Request-Id";
  private static final String SIGNATURE_HEADER = "X-CommerceFlow-Signature";

  private final RestClient client;
  private final String baseUrl;
  private final String secret;

  public NotificationServiceClient(
    RestClient.Builder builder,
    @Value("${commerceflow.notification.service.base-url:http://localhost:18086}") String baseUrl,
    @Value("${commerceflow.notification.service.secret:demo-notification-internal-secret}") String secret
  ) {
    HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2))
      .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    this.client = builder.requestFactory(requestFactory).build();
    this.baseUrl = baseUrl;
    this.secret = secret;
  }

  public List<CustomerNotification> list(Long customerId) {
    String target = "/internal/v1/customers/" + customerId + "/notifications?limit=20";
    try {
      CustomerNotification[] result = client.get().uri(URI.create(baseUrl + target))
        .headers(headers -> sign(headers, "GET", target)).retrieve()
        .body(CustomerNotification[].class);
      return result == null ? List.of() : Arrays.asList(result);
    } catch (RuntimeException exception) {
      throw new NotificationServiceUnavailableException(exception);
    }
  }

  public int unreadCount(Long customerId) {
    String target = "/internal/v1/customers/" + customerId + "/notifications/unread-count";
    try {
      Map<?, ?> result = client.get().uri(URI.create(baseUrl + target))
        .headers(headers -> sign(headers, "GET", target)).retrieve().body(Map.class);
      Object count = result == null ? null : result.get("count");
      return count instanceof Number number ? number.intValue() : 0;
    } catch (RuntimeException exception) {
      throw new NotificationServiceUnavailableException(exception);
    }
  }

  public void markRead(Long customerId, String eventId) {
    String decodedEventId = UriUtils.decode(eventId, StandardCharsets.UTF_8);
    exchangePatch("/internal/v1/customers/" + customerId + "/notifications/"
      + decodedEventId + "/read");
  }

  public void markAllRead(Long customerId) {
    exchangePatch("/internal/v1/customers/" + customerId + "/notifications/read-all");
  }

  private void exchangePatch(String target) {
    try {
      client.patch().uri(URI.create(baseUrl + target))
        .headers(headers -> sign(headers, "PATCH", target))
        .retrieve().toBodilessEntity();
    } catch (RuntimeException exception) {
      throw new NotificationServiceUnavailableException(exception);
    }
  }

  private void sign(HttpHeaders headers, String method, String target) {
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String requestId = UUID.randomUUID().toString();
    headers.set(SERVICE_HEADER, "commerce-service");
    headers.set(TIMESTAMP_HEADER, timestamp);
    headers.set(REQUEST_ID_HEADER, requestId);
    headers.set(SIGNATURE_HEADER, signature(method, target, timestamp, requestId));
  }

  private String signature(String method, String target, String timestamp, String requestId) {
    try {
      String canonical = method + "\n" + target + "\n" + timestamp + "\n" + requestId;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign notification request", exception);
    }
  }
}
