package vip.fairy.commerceflow.commerce.inventory.infrastructure.transport;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryEvidenceV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryEvidenceV1.Receipt;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@Component
public class DeliveredInventoryRemoteClient {

  private static final String TARGET = "/internal/v1/inventory/delivered-reconciliations";

  private final RestClient client;
  private final InventoryInternalRequestSigner signer;
  private final ObjectMapper mapper;

  public DeliveredInventoryRemoteClient(RestClient.Builder builder, InventoryInternalRequestSigner signer,
                                        ObjectMapper mapper,
                                        @Value("${commerceflow.inventory.remote.base-url:http://localhost:18088}")
                                        String baseUrl) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(3));
    this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    this.signer = signer;
    this.mapper = mapper;
  }

  public Receipt reconcile(DeliveredInventoryEvidenceV1 evidence) {
    String payload = mapper.writeValueAsString(evidence);
    try {
      Receipt receipt = client.post().uri(TARGET).contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> signer.sign(headers, "POST", TARGET, signer.contentHash(payload)))
        .body(payload).retrieve().body(Receipt.class);
      if (receipt == null || receipt.orderId() != evidence.orderId() || receipt.movementIds() == null
          || receipt.movementIds().size() != evidence.lines().size()
          || receipt.movementIds().stream().anyMatch(id -> id == null || id <= 0)) {
        throw new InventoryRemoteUnavailableException(
          new IllegalStateException("Invalid inventory reconciliation receipt"));
      }
      return receipt;
    } catch (RestClientResponseException exception) {
      if (List.of(400, 409).contains(exception.getStatusCode().value())) {
        throw new BusinessRuleException(rejectionMessage(exception));
      }
      throw new InventoryRemoteUnavailableException(exception);
    } catch (RestClientException exception) {
      throw new InventoryRemoteUnavailableException(exception);
    }
  }

  private String rejectionMessage(RestClientResponseException exception) {
    String fallback = "Warehouse evidence was rejected";
    try {
      return mapper.readTree(exception.getResponseBodyAsString()).path("detail").asText(fallback);
    } catch (JacksonException invalidResponse) {
      return fallback;
    }
  }
}
