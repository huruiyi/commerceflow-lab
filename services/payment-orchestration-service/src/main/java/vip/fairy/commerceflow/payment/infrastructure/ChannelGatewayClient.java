package vip.fairy.commerceflow.payment.infrastructure;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentChannelV1;

@Component
public class ChannelGatewayClient {

  private final RestClient client;
  private final ObjectMapper mapper;
  private final String baseUrl;

  public ChannelGatewayClient(ObjectMapper mapper,
                              @Value("${commerceflow.payment-platform.base-url}") String baseUrl) {
    this.client = RestClient.builder().build();
    this.mapper = mapper;
    this.baseUrl = baseUrl;
  }

  public Result create(String paymentNumber, PaymentChannelV1 channel, String subject,
                       BigDecimal amount, String currency, boolean simulateFailure) {
    String path = "/api/channel/transactions";
    String body = mapper.writeValueAsString(Map.of(
      "merchantRequestNo", paymentNumber,
      "channel", channel.name(),
      "subject", subject,
      "amount", amount,
      "currency", currency,
      "faultScenario", simulateFailure ? "CREATE_FAILURE" : "NONE"));
    Credentials credentials = credentials(channel);
    return client.post().uri(baseUrl + path).contentType(MediaType.APPLICATION_JSON)
      .headers(headers -> sign(headers, "POST", path, body, credentials))
      .body(body).retrieve().body(Result.class);
  }

  public Result close(PaymentChannelV1 channel, String channelTransactionNo) {
    String path = "/api/channel/transactions/" + channelTransactionNo + "/close";
    Credentials credentials = credentials(channel);
    return client.post().uri(baseUrl + path)
      .headers(headers -> sign(headers, "POST", path, "", credentials))
      .retrieve().body(Result.class);
  }

  private Credentials credentials(PaymentChannelV1 channel) {
    String name = channel.name().toLowerCase().replace('_', '-');
    return new Credentials("commerceflow-" + name, "demo-" + name + "-secret");
  }

  private void sign(HttpHeaders headers, String method, String path, String body,
                    Credentials credentials) {
    try {
      String timestamp = Long.toString(Instant.now().getEpochSecond());
      String nonce = UUID.randomUUID().toString().replace("-", "");
      String requestId = UUID.randomUUID().toString().replace("-", "");
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(body.getBytes(StandardCharsets.UTF_8)));
      String canonical = String.join("\n", method, path, timestamp, nonce, requestId, hash);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(credentials.secret().getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"));
      headers.set("X-App-Id", credentials.appId());
      headers.set("X-Signature-Algorithm", "HMAC_SHA256");
      headers.set("X-Timestamp", timestamp);
      headers.set("X-Nonce", nonce);
      headers.set("X-Request-Id", requestId);
      headers.set("X-Body-SHA256", hash);
      headers.set("X-Signature", Base64.getEncoder().encodeToString(
        mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign payment platform request", exception);
    }
  }

  record Credentials(String appId, String secret) {
  }

  public record Result(String channelTransactionNo, String merchantRequestNo, String channel,
                       String subject, BigDecimal amount, String currency, String status,
                       String faultScenario, String actionType, String checkoutUrl, String qrPayload,
                       LocalDateTime expiresAt, LocalDateTime paidAt, LocalDateTime closedAt,
                       LocalDateTime createdAt) {

    public Instant expiresInstant() {
      return expiresAt.toInstant(ZoneOffset.UTC);
    }
  }
}
