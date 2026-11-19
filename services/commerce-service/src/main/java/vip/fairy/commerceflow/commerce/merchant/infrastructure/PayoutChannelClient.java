package vip.fairy.commerceflow.commerce.merchant.infrastructure;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.merchant.domain.MerchantPayout;

@Component
public class PayoutChannelClient {

  private static final String PATH = "/api/channel/payouts";
  private final RestClient client;
  private final ObjectMapper mapper;
  private final String secret;

  public PayoutChannelClient(RestClient.Builder builder, ObjectMapper mapper,
    @Value("${PAYOUT_PLATFORM_BASE_URL:http://localhost:14201}") String base,
    @Value("${PAYOUT_PLATFORM_APP_SECRET:demo-merchant-payout-secret}") String secret) {
    this.mapper = mapper;
    this.secret = secret;
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var request = new JdkClientHttpRequestFactory(http);
    request.setReadTimeout(Duration.ofSeconds(3));
    this.client = builder.baseUrl(base).requestFactory(request).build();
  }

  public Optional<Outcome> find(String id) {
    try {
      return Optional.ofNullable(send(HttpMethod.GET, PATH + "/" + id, ""));
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().value() == 404) return Optional.empty();
      throw exception;
    }
  }

  public Outcome create(MerchantPayout value) {
    return send(HttpMethod.POST, PATH, mapper.writeValueAsString(Map.of(
      "attemptId", value.id(), "statementId", value.statementId(), "beneficiaryId", value.merchantId(),
      "amount", value.amount(), "simulateFailure", value.simulateFailure())));
  }

  private Outcome send(HttpMethod method, String path, String raw) {
    var request = client.method(method).uri(path).headers(headers -> sign(headers, method.name(), path, raw));
    return (raw.isEmpty() ? request : request.contentType(MediaType.APPLICATION_JSON).body(raw))
      .retrieve().body(Outcome.class);
  }

  private void sign(HttpHeaders headers, String method, String path, String body) {
    try {
      String time = Long.toString(Instant.now().getEpochSecond());
      String nonce = UUID.randomUUID().toString().replace("-", "");
      String request = UUID.randomUUID().toString().replace("-", "");
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(body.getBytes(StandardCharsets.UTF_8)));
      String canonical = String.join("\n", method, path, time, nonce, request, hash);
      headers.set("X-App-Id", "commerceflow-payout");
      headers.set("X-Signature-Algorithm", "HMAC_SHA256");
      headers.set("X-Timestamp", time);
      headers.set("X-Nonce", nonce);
      headers.set("X-Request-Id", request);
      headers.set("X-Body-SHA256", hash);
      headers.set("X-Signature", Base64.getEncoder().encodeToString(hmac(secret, canonical)));
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot sign payout request", exception);
    }
  }

  public static byte[] hmac(String secret, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot compute payout signature", exception);
    }
  }

  public record Outcome(String attemptId, String statementId, Long beneficiaryId, BigDecimal amount,
                        String currency, String channelPayoutNo, String status, boolean simulateFailure,
                        Instant createdAt, Instant resolvedAt, Instant notifiedAt) {
  }
}
