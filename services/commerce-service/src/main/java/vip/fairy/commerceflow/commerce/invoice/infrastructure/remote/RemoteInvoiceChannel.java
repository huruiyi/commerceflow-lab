package vip.fairy.commerceflow.commerce.invoice.infrastructure.remote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.invoice.application.InvoiceChannel;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Attempt;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Kind;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "commerceflow.invoice", name = "enabled", havingValue = "true")
public class RemoteInvoiceChannel implements InvoiceChannel {

  private static final String REQUESTS = "/api/invoice-requests/";
  private static final String TIMESTAMP = "X-Invoice-Timestamp";
  private static final String SIGNATURE = "X-Invoice-Signature";
  private static final byte[] EMPTY = new byte[0];
  private final RestClient client;
  private final ObjectMapper json;
  private final String baseUrl;
  private final String requestSecret;
  private final String callbackSecret;

  public RemoteInvoiceChannel(ObjectMapper json,
                              @Value("${commerceflow.invoice.base-url}") String baseUrl,
                              @Value("${commerceflow.invoice.request-secret}") String requestSecret,
                              @Value("${commerceflow.invoice.callback-secret}") String callbackSecret) {
    if (requestSecret.length() < 32 || callbackSecret.length() < 32 || requestSecret.equals(callbackSecret)) {
      throw new IllegalArgumentException("Configure distinct invoice signing secrets of at least 32 characters");
    }
    this.json = json;
    this.baseUrl = baseUrl;
    this.requestSecret = requestSecret;
    this.callbackSecret = callbackSecret;
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(2));
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public Result resolve(Attempt attempt) {
    String query = REQUESTS + attempt.requestId();
    try {
      return client.get().uri(URI.create(baseUrl + query))
        .headers(headers -> {
          String timestamp = Long.toString(Instant.now().getEpochSecond());
          headers.set(TIMESTAMP, timestamp);
          headers.set(SIGNATURE, HexFormat.of().formatHex(mac(requestSecret, timestamp, "GET", query, EMPTY)));
        }).retrieve().body(Result.class);
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode().value() == 404 &&
        "REQUEST_NOT_FOUND".equals(json.readTree(ex.getResponseBodyAsByteArray()).path("code").asText())) {
        return submit(attempt);
      }
      throw ex;
    }
  }

  private Result submit(Attempt attempt) {
    boolean issue = attempt.kind() == Kind.ISSUE;
    String path = (issue ? REQUESTS : "/api/red-invoice-requests/") + attempt.requestId();
    var body = issue
      ? Map.of("applicationNo", attempt.referenceNo(), "paymentNo", attempt.paymentNo(),
        "amount", attempt.amount(), "currency", attempt.currency())
      : Map.of("refundNo", attempt.referenceNo(), "originalInvoiceNo", attempt.originalInvoiceNo(),
        "amount", attempt.amount(), "currency", attempt.currency());
    byte[] bytes = json.writeValueAsBytes(body);
    try {
      return client.post().uri(URI.create(baseUrl + path)).contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> {
          String timestamp = Long.toString(Instant.now().getEpochSecond());
          headers.set(TIMESTAMP, timestamp);
          headers.set(SIGNATURE, HexFormat.of().formatHex(mac(requestSecret, timestamp, "POST", path, bytes)));
        }).body(bytes).retrieve().body(Result.class);
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 404 || ex.getStatusCode().value() == 409) {
        throw new Rejected();
      }
      throw ex;
    }
  }

  @Override
  public boolean authenticReceipt(String timestamp, String signature, String path, byte[] body) {
    if (timestamp == null || !timestamp.matches("[0-9]{1,19}") ||
      signature == null || !signature.matches("[0-9a-fA-F]{64}")) {
      return false;
    }
    long seconds;
    try {
      seconds = Long.parseLong(timestamp);
    } catch (NumberFormatException ex) {
      return false;
    }
    long now = Instant.now().getEpochSecond();
    return seconds >= now - 300 && seconds <= now + 300 &&
      MessageDigest.isEqual(HexFormat.of().parseHex(signature), mac(callbackSecret, timestamp, "POST", path, body));
  }

  private static byte[] mac(String secret, String timestamp, String method, String path, byte[] body) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + "\n" + method + "\n" + path + "\n").getBytes(StandardCharsets.UTF_8));
      return mac.doFinal(body);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Invoice HMAC unavailable", ex);
    }
  }
}
