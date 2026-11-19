package vip.fairy.commerceflow.commerce.inventory.infrastructure.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryReservationQuery;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.StockAvailabilityQuery;
import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;

@Component
public class InventoryRemoteCommandClient implements InventoryReservationQuery, StockAvailabilityQuery {

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

  @Override
  public Map<Long, Integer> activeReservations(Long orderId) {
    String target = "/internal/v1/inventory/reservations/" + orderId;
    try {
      return client.get().uri(URI.create(baseUrl + target))
        .headers(headers -> signer.sign(headers, "GET", target, signer.contentHash("")))
        .retrieve().requiredBody(new ParameterizedTypeReference<>() {});
    } catch (RuntimeException exception) {
      throw new InventoryRemoteUnavailableException(exception);
    }
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

  @Override
  public Map<Long, Stock> availability(Collection<Long> productIds) {
    List<Long> ids = productIds.stream().distinct().toList();
    Map<Long, Stock> stocks = new HashMap<>();
    String target = "/internal/v1/inventory/availability";
    try {
      for (int start = 0; start < ids.size(); start += 200) {
        String payload = mapper.writeValueAsString(ids.subList(start, Math.min(start + 200, ids.size())));
        List<Stock> batch = client.post().uri(URI.create(baseUrl + target))
          .headers(headers -> signer.sign(headers, "POST", target, signer.contentHash(payload)))
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload).retrieve().requiredBody(new ParameterizedTypeReference<>() {});
        batch.forEach(stock -> stocks.put(stock.productId(), stock));
      }
      return stocks;
    } catch (RuntimeException exception) {
      throw new ExternalServiceUnavailableException("STOCK_LOOKUP_UNAVAILABLE",
        "Stock availability could not be checked", exception);
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
