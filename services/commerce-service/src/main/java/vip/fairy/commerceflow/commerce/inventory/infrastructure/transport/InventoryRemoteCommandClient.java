package vip.fairy.commerceflow.commerce.inventory.infrastructure.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
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
  private final InventoryInternalRequestSigner signer;
  private final String baseUrl;

  public InventoryRemoteCommandClient(
    RestClient.Builder builder,
    ObjectMapper mapper,
    InventoryInternalRequestSigner signer,
    @Value("${commerceflow.inventory.remote.base-url:http://localhost:18088}") String baseUrl
  ) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    this.client = builder.requestFactory(requestFactory).build();
    this.mapper = mapper;
    this.signer = signer;
    this.baseUrl = baseUrl;
  }

  public InventoryCommandResultV1 send(InventoryCommandV1 command) {
    String payload = payload(command);
    String contentHash = signer.contentHash(payload);
    try {
      InventoryCommandResultV1 result = client.post().uri(URI.create(baseUrl + TARGET))
        .headers(headers -> {
          signer.sign(headers, "POST", TARGET, contentHash);
          headers.set("traceparent", command.traceparent());
        })
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

}
