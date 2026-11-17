package vip.fairy.commerceflow.commerce.payment.infrastructure.remote;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseResultV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCreateCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentSnapshotV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundCreateCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundQueryV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundSnapshotV1;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Component
public class RemotePaymentOrchestrationAdapter implements PaymentOrchestrationV1 {

  private static final String ROOT = "/internal/v1/payments";

  private final RestClient client;
  private final ObjectMapper mapper;
  private final PaymentInternalRequestSigner signer;
  private final String baseUrl;

  public RemotePaymentOrchestrationAdapter(
    RestClient.Builder builder,
    ObjectMapper mapper,
    PaymentInternalRequestSigner signer,
    @Value("${commerceflow.payment.remote.base-url:http://localhost:18090}") String baseUrl
  ) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(5));
    this.client = builder.requestFactory(requestFactory).build();
    this.mapper = mapper;
    this.signer = signer;
    this.baseUrl = baseUrl;
  }

  @Override
  public PaymentSnapshotV1 create(PaymentCreateCommandV1 command) {
    command.validate();
    return post(ROOT + "/create", command, PaymentSnapshotV1.class);
  }

  @Override
  public Optional<PaymentSnapshotV1> latest(PaymentQueryV1 query) {
    return Optional.ofNullable(query(query).payment());
  }

  @Override
  public Optional<RefundSnapshotV1> refund(PaymentQueryV1 query) {
    return Optional.ofNullable(query(query).refund());
  }

  @Override
  public Optional<RefundSnapshotV1> refund(RefundQueryV1 query) {
    query.validate();
    return Optional.ofNullable(post(ROOT + "/refund-query", query, RefundSnapshotV1.class));
  }

  @Override
  public RefundSnapshotV1 requestRefund(RefundCreateCommandV1 command) {
    command.validate();
    post(ROOT + "/refunds", command, Object.class);
    return refund(new PaymentQueryV1(PaymentQueryV1.VERSION, command.orderId()))
      .orElseThrow(() -> unavailable("Payment service accepted a refund but returned no snapshot",
        null));
  }

  @Override
  public PaymentCloseResultV1 closePending(PaymentCloseCommandV1 command) {
    command.validate();
    return post(ROOT + "/close", command, PaymentCloseResultV1.class);
  }

  private QueryResult query(PaymentQueryV1 query) {
    query.validate();
    return post(ROOT + "/query", query, QueryResult.class);
  }

  private <T> T post(String target, Object body, Class<T> responseType) {
    String payload = serialize(body);
    try {
      return client.post().uri(URI.create(baseUrl + target)).contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> signer.sign(headers, target, payload)).body(payload).retrieve()
        .body(responseType);
    } catch (RestClientResponseException exception) {
      throw remoteFailure(exception);
    } catch (ExternalServiceUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable("Payment service is unavailable", exception);
    }
  }

  private String serialize(Object body) {
    try {
      return mapper.writeValueAsString(body);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Payment request cannot be serialized", exception);
    }
  }

  private RuntimeException remoteFailure(RestClientResponseException exception) {
    String message = remoteMessage(exception);
    return switch (exception.getStatusCode().value()) {
      case 400 -> new IllegalArgumentException(message);
      case 404 -> new ResourceNotFoundException(message);
      case 409 -> new BusinessRuleException(message);
      default -> unavailable("Payment service rejected the request", exception);
    };
  }

  private String remoteMessage(RestClientResponseException exception) {
    try {
      JsonNode body = mapper.readTree(exception.getResponseBodyAsString());
      if (body.hasNonNull("message") && !body.get("message").asText().isBlank()) {
        return body.get("message").asText();
      }
      if (body.hasNonNull("detail") && !body.get("detail").asText().isBlank()) {
        return body.get("detail").asText();
      }
    } catch (JacksonException ignored) {
      // A stable local message is returned when a proxy sends a non-JSON error body.
    }
    return "Payment request was rejected";
  }

  private ExternalServiceUnavailableException unavailable(String message, Throwable cause) {
    return new ExternalServiceUnavailableException("PAYMENT_SERVICE_UNAVAILABLE", message, cause);
  }

  private record QueryResult(PaymentSnapshotV1 payment, RefundSnapshotV1 refund) {
  }
}
