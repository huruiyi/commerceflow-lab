package vip.fairy.commerceflow.commerce.inventory.infrastructure.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.commerce.inventory.domain.MerchantInventoryItem;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Component
public class MerchantInventoryRemoteClient {

  private static final ParameterizedTypeReference<List<MerchantInventoryItem>> ITEM_LIST =
    new ParameterizedTypeReference<>() { };
  private static final ParameterizedTypeReference<List<InventoryMovementView>> MOVEMENT_LIST =
    new ParameterizedTypeReference<>() { };

  private final RestClient client;
  private final ObjectMapper mapper;
  private final InventoryInternalRequestSigner signer;
  private final String baseUrl;

  public MerchantInventoryRemoteClient(
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

  public List<MerchantInventoryItem> list(Long merchantId) {
    String target = merchantTarget(merchantId) + "/items";
    try {
      List<MerchantInventoryItem> result = client.get().uri(URI.create(baseUrl + target))
        .headers(headers -> signer.sign(headers, "GET", target, signer.contentHash("")))
        .retrieve().body(ITEM_LIST);
      return result == null ? List.of() : result;
    } catch (RestClientResponseException exception) {
      throw remoteFailure(exception);
    } catch (RuntimeException exception) {
      throw new InventoryRemoteUnavailableException(exception);
    }
  }

  public List<InventoryMovementView> movements(Long merchantId, Long productId) {
    String target = merchantTarget(merchantId) + "/items/" + productId + "/movements";
    try {
      List<InventoryMovementView> result = client.get().uri(URI.create(baseUrl + target))
        .headers(headers -> signer.sign(headers, "GET", target, signer.contentHash("")))
        .retrieve().body(MOVEMENT_LIST);
      return result == null ? List.of() : result;
    } catch (RestClientResponseException exception) {
      throw remoteFailure(exception);
    } catch (RuntimeException exception) {
      throw new InventoryRemoteUnavailableException(exception);
    }
  }

  public MerchantInventoryItem adjust(Long merchantId, Long productId, int delta, String reason, Long warehouseId) {
    String target = merchantTarget(merchantId) + "/items/" + productId + "/adjustments";
    String payload = payload(delta, reason, warehouseId);
    try {
      MerchantInventoryItem result = client.post().uri(URI.create(baseUrl + target))
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> signer.sign(headers, "POST", target, signer.contentHash(payload)))
        .body(payload).retrieve().body(MerchantInventoryItem.class);
      if (result == null) {
        throw new InventoryRemoteUnavailableException(
          new IllegalStateException("Inventory service returned no adjustment result"));
      }
      return result;
    } catch (RestClientResponseException exception) {
      throw remoteFailure(exception);
    } catch (InventoryRemoteUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new InventoryRemoteUnavailableException(exception);
    }
  }

  private String merchantTarget(Long merchantId) {
    return "/internal/v1/inventory/merchants/" + merchantId;
  }

  private String payload(int delta, String reason, Long warehouseId) {
    try {
      return mapper.writeValueAsString(warehouseId == null ? Map.of("delta", delta, "reason", reason)
        : Map.of("delta", delta, "reason", reason, "warehouseId", warehouseId));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory adjustment cannot be serialized", exception);
    }
  }

  private RuntimeException remoteFailure(RestClientResponseException exception) {
    String message = remoteMessage(exception);
    return switch (exception.getStatusCode().value()) {
      case 400 -> new IllegalArgumentException(message);
      case 404 -> new ResourceNotFoundException(message);
      case 409 -> new BusinessRuleException(message);
      default -> new InventoryRemoteUnavailableException(exception);
    };
  }

  private String remoteMessage(RestClientResponseException exception) {
    try {
      JsonNode body = mapper.readTree(exception.getResponseBodyAsString());
      if (body.hasNonNull("detail") && !body.get("detail").asText().isBlank()) {
        return body.get("detail").asText();
      }
    } catch (JacksonException ignored) {
      // The stable local fallback below is used when a remote proxy returns a non-JSON body.
    }
    return "Inventory request was rejected";
  }
}
