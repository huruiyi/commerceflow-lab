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
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryLineCreditV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryEvidenceV1.Receipt;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.StocktakeV1;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

@Component
public class DeliveredInventoryRemoteClient implements InventoryLineCreditV1 {

  private static final String TARGET = "/internal/v1/inventory/delivered-reconciliations";
  private static final String STOCKTAKES = "/internal/v1/inventory/stocktakes";

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

  @Override
  public boolean credit(InventoryLineCreditV1.Credit credit, boolean apply) {
    String target = "/internal/v1/inventory/line-credits";
    String payload = mapper.writeValueAsString(new LineCreditRequest(credit, apply));
    Boolean result = client.post().uri(target).contentType(MediaType.APPLICATION_JSON)
      .headers(headers -> signer.sign(headers, "POST", target, signer.contentHash(payload)))
      .body(payload).retrieve().body(Boolean.class);
    if (result == null) throw new IllegalStateException("Missing inventory line credit response");
    return result;
  }

  @Override
  public List<Source> sources(Origin request) {
    String target = "/internal/v1/inventory/line-credits/sources";
    String payload = mapper.writeValueAsString(request);
    try {
      Source[] result = client.post().uri(target).contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> signer.sign(headers, "POST", target, signer.contentHash(payload)))
        .body(payload).retrieve().body(Source[].class);
      if (result == null) throw new IllegalStateException("Missing inventory outbound sources");
      return List.of(result);
    } catch (RestClientException failure) {
      throw new InventoryRemoteUnavailableException(failure);
    }
  }

  private record LineCreditRequest(InventoryLineCreditV1.Credit credit, boolean apply) {
  }

  @Override
  public boolean exchange(Credit credit, ExchangeAction action) {
    String target = "/internal/v1/inventory/line-credits/exchange";
    String payload = mapper.writeValueAsString(new ExchangeRequest(credit, action));
    Boolean result = client.post().uri(target).contentType(MediaType.APPLICATION_JSON)
      .headers(headers -> signer.sign(headers, "POST", target, signer.contentHash(payload)))
      .body(payload).retrieve().body(Boolean.class);
    if (result == null) throw new IllegalStateException("Missing replacement inventory response");
    return result;
  }

  private record ExchangeRequest(Credit credit, ExchangeAction action) {
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

  public StocktakeV1.Baseline stocktakeBaseline(long productId) {
    String target = STOCKTAKES + "/baseline/" + productId;
    try {
      var baseline = client.get().uri(target)
        .headers(headers -> signer.sign(headers, "GET", target, signer.contentHash("")))
        .retrieve().body(StocktakeV1.Baseline.class);
      if (baseline == null || baseline.productId() != productId) {
        throw new InventoryRemoteUnavailableException(new IllegalStateException("Invalid stocktake baseline"));
      }
      return baseline;
    } catch (RestClientResponseException exception) {
      throw stocktakeRejection(exception);
    } catch (RestClientException exception) {
      throw new InventoryRemoteUnavailableException(exception);
    }
  }

  public StocktakeV1.Receipt applyStocktake(StocktakeV1 stocktake) {
    String payload = mapper.writeValueAsString(stocktake);
    try {
      var receipt = client.post().uri(STOCKTAKES).contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> signer.sign(headers, "POST", STOCKTAKES, signer.contentHash(payload)))
        .body(payload).retrieve().body(StocktakeV1.Receipt.class);
      int adjustment = stocktake.countedAvailable() - stocktake.baseline().available();
      if (receipt == null || !stocktake.reference().equals(receipt.reference())
          || receipt.adjustment() != adjustment
          || (adjustment == 0 ? receipt.movementId() != null
                              : receipt.movementId() == null || receipt.movementId() <= 0)) {
        throw new InventoryRemoteUnavailableException(new IllegalStateException("Invalid stocktake receipt"));
      }
      return receipt;
    } catch (RestClientResponseException exception) {
      throw stocktakeRejection(exception);
    } catch (RestClientException exception) {
      throw new InventoryRemoteUnavailableException(exception);
    }
  }

  private RuntimeException stocktakeRejection(RestClientResponseException exception) {
    if (List.of(400, 409).contains(exception.getStatusCode().value())) {
      return new BusinessRuleException(rejectionMessage(exception));
    }
    return new InventoryRemoteUnavailableException(exception);
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
