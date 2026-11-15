package vip.fairy.commerceflow.commerce.catalog.infrastructure.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogPriceUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogProfileUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogStatusUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.merchant.CatalogTranslationUpdateRequest;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductSnapshotV1;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogSnapshotBatchRequest;
import vip.fairy.commerceflow.commerce.catalog.application.CatalogServiceUnavailableException;
import vip.fairy.commerceflow.commerce.shared.application.OptimisticVersionConflictException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class CatalogServiceClient {

  private static final String SERVICE_HEADER = "X-CommerceFlow-Service";
  private static final String TIMESTAMP_HEADER = "X-CommerceFlow-Timestamp";
  private static final String REQUEST_ID_HEADER = "X-CommerceFlow-Request-Id";
  private static final String SIGNATURE_HEADER = "X-CommerceFlow-Signature";

  private final RestClient client;
  private final String baseUrl;
  private final String secret;

  public CatalogServiceClient(
    RestClient.Builder builder,
    @Value("${commerceflow.catalog.service.base-url:http://localhost:18087}") String baseUrl,
    @Value("${commerceflow.catalog.service.secret:demo-catalog-internal-secret}") String secret) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    this.client = builder.requestFactory(requestFactory).build();
    this.baseUrl = baseUrl;
    this.secret = secret;
  }

  public List<CatalogProductV1> list(String locale) {
    return getProducts(target("/internal/v1/products", locale));
  }

  public CatalogProductV1 detail(Long productId, String locale) {
    return getProduct(target("/internal/v1/products/" + productId, locale));
  }

  public List<CatalogProductSnapshotV1> snapshots(List<Long> productIds) {
    String target = "/internal/v1/products/snapshots";
    try {
      CatalogProductSnapshotV1[] body = client.post().uri(URI.create(baseUrl + target))
        .headers(headers -> sign(headers, "POST", target)).body(new CatalogSnapshotBatchRequest(productIds))
        .retrieve().body(CatalogProductSnapshotV1[].class);
      return body == null ? List.of() : Arrays.asList(body);
    } catch (RuntimeException exception) {
      throw translated(exception);
    }
  }

  public List<CatalogProductV1> merchantList(Long merchantId) {
    String target = "/internal/v1/merchant/products";
    try {
      CatalogProductV1[] body = client.get().uri(URI.create(baseUrl + target))
        .headers(headers -> merchant(headers, "GET", target, merchantId, null, null))
        .retrieve().body(CatalogProductV1[].class);
      return body == null ? List.of() : Arrays.asList(body);
    } catch (RuntimeException exception) {
      throw translated(exception);
    }
  }

  public CatalogProductV1 updateProfile(Long merchantId, Long productId,
                                        CatalogProfileUpdateRequest request,
                                        String correlationId, String causationId) {
    return put(merchantId, productId, "profile", request, correlationId, causationId);
  }

  public CatalogProductV1 updatePrice(Long merchantId, Long productId,
                                      CatalogPriceUpdateRequest request,
                                      String correlationId, String causationId) {
    return put(merchantId, productId, "price", request, correlationId, causationId);
  }

  public CatalogProductV1 updateStatus(Long merchantId, Long productId,
                                       CatalogStatusUpdateRequest request,
                                       String correlationId, String causationId) {
    return put(merchantId, productId, "status", request, correlationId, causationId);
  }

  public CatalogProductV1 updateTranslation(Long merchantId, Long productId, String locale,
                                            CatalogTranslationUpdateRequest request,
                                            String correlationId, String causationId) {
    return put(merchantId, productId, "translations/" + locale, request,
      correlationId, causationId);
  }

  private List<CatalogProductV1> getProducts(String target) {
    try {
      CatalogProductV1[] body = client.get().uri(URI.create(baseUrl + target))
        .headers(headers -> sign(headers, "GET", target)).retrieve()
        .body(CatalogProductV1[].class);
      return body == null ? List.of() : Arrays.asList(body);
    } catch (RuntimeException exception) {
      throw translated(exception);
    }
  }

  private CatalogProductV1 getProduct(String target) {
    try {
      return client.get().uri(URI.create(baseUrl + target))
        .headers(headers -> sign(headers, "GET", target)).retrieve().body(CatalogProductV1.class);
    } catch (RuntimeException exception) {
      throw translated(exception);
    }
  }

  private CatalogProductV1 put(Long merchantId, Long productId, String suffix, Object request,
                               String correlationId, String causationId) {
    String target = "/internal/v1/merchant/products/" + productId + "/" + suffix;
    try {
      return client.put().uri(URI.create(baseUrl + target))
        .headers(headers -> merchant(headers, "PUT", target, merchantId,
          correlationId, causationId))
        .body(request).retrieve().body(CatalogProductV1.class);
    } catch (RuntimeException exception) {
      throw translated(exception);
    }
  }

  private String target(String path, String locale) {
    return locale == null || locale.isBlank() ? path
      : path + "?lang=" + UriUtils.encodeQueryParam(locale, StandardCharsets.UTF_8);
  }

  private void merchant(HttpHeaders headers, String method, String target, Long merchantId,
                        String correlationId, String causationId) {
    sign(headers, method, target);
    headers.set("X-Merchant-Id", merchantId.toString());
    if (correlationId != null && !correlationId.isBlank()) {
      headers.set("X-Correlation-Id", correlationId);
    }
    if (causationId != null && !causationId.isBlank()) {
      headers.set("X-Causation-Id", causationId);
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
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String canonical = method + "\n" + target + "\n" + timestamp + "\n" + requestId;
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not sign catalog request", exception);
    }
  }

  private RuntimeException translated(RuntimeException exception) {
    if (exception instanceof HttpClientErrorException.NotFound) {
      return new ResourceNotFoundException("Product not found");
    }
    if (exception instanceof HttpClientErrorException.Conflict) {
      return new OptimisticVersionConflictException("CATALOG_VERSION_CONFLICT",
        "Product changed since it was read; reload it before retrying");
    }
    return new CatalogServiceUnavailableException(exception);
  }
}
