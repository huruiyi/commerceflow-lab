package vip.fairy.commerceflow.commerce.inventory.infrastructure.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;

@Component
public class InventoryRemoteCommandClient {

  private static final String TARGET = "/internal/v1/inventory/commands";

  private final RestClient client;
  private final ObjectMapper mapper;
  private final String baseUrl;
  private final String secret;

  public InventoryRemoteCommandClient(
    RestClient.Builder builder,
    ObjectMapper mapper,
    @Value("${commerceflow.inventory.remote.base-url:http://localhost:18088}") String baseUrl,
    @Value("${commerceflow.inventory.remote.secret:demo-inventory-internal-secret}") String secret
  ) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    this.client = builder.requestFactory(requestFactory).build();
    this.mapper = mapper;
    this.baseUrl = baseUrl;
    this.secret = secret;
  }

  public InventoryCommandResultV1 send(InventoryCommandV1 command) {
    String payload = payload(command);
    String contentHash = sha256(payload);
    try {
      InventoryCommandResultV1 result = client.post().uri(URI.create(baseUrl + TARGET))
        .headers(headers -> sign(headers, contentHash, command.traceparent()))
        .body(payload).retrieve().body(InventoryCommandResultV1.class);
      if (result == null) {
        throw new IllegalStateException("Inventory service returned no result");
      }
      result.validate();
      if (!command.commandId().equals(result.commandId())
          || command.commandType() != result.commandType()
          || !command.correlationId().equals(result.correlationId())) {
        throw new IllegalStateException("Inventory result does not match the command");
      }
      return result;
    } catch (RuntimeException exception) {
      if (exception instanceof IllegalStateException) {
        throw exception;
      }
      throw new InventoryRemoteUnavailableException(exception);
    }
  }

  private String payload(InventoryCommandV1 command) {
    try {
      return mapper.writeValueAsString(command);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory command cannot be serialized", exception);
    }
  }

  private void sign(HttpHeaders headers, String contentHash, String traceparent) {
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String requestId = UUID.randomUUID().toString();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    headers.set("X-CommerceFlow-Service", "commerce-service");
    headers.set("X-CommerceFlow-Timestamp", timestamp);
    headers.set("X-CommerceFlow-Request-Id", requestId);
    headers.set("X-CommerceFlow-Content-SHA256", contentHash);
    headers.set("X-CommerceFlow-Signature",
      hmac("POST\n" + TARGET + "\n" + timestamp + "\n" + requestId + "\n" + contentHash));
    headers.set("traceparent", traceparent);
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not hash inventory command", exception);
    }
  }

  private String hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign inventory command", exception);
    }
  }
}
