package vip.fairy.commerceflow.commerce.payment.application;

import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import java.math.BigDecimal;
import java.util.Map;

import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import vip.fairy.commerceflow.commerce.payment.infrastructure.PaymentSigning;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentGatewayClient {

  private final RestClient client;
  private final ObjectMapper mapper;
  private final String baseUrl;

  public PaymentGatewayClient(
    RestClient.Builder builder,
    ObjectMapper mapper,
    @Value("${commerceflow.payment-platform.base-url:http://localhost:18082}") String baseUrl
  ) {
    this.client = builder.build();
    this.mapper = mapper;
    this.baseUrl = baseUrl;
  }

  @Retry(name = "paymentPlatform")
  @CircuitBreaker(name = "paymentPlatform")
  @Bulkhead(name = "paymentPlatform")
  public Result create(String number, PaymentChannel channel, String subject, BigDecimal amount,
                       String currency, String scenario) {
    try {
      String body = mapper.writeValueAsString(Map.of("merchantRequestNo", number, "channel", channel.name(),
        "subject", subject, "amount", amount, "currency", currency, "faultScenario", scenario));
      String path = "/api/channel/transactions";
      var credentials = PaymentSigning.credentials(channel);
      return client.post().uri(baseUrl + path).contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> PaymentSigning.apply(headers, "POST", path, body,
          credentials.appId(), credentials.secret()))
        .body(body).retrieve().body(Result.class);
    } catch (Exception exception) {
      throw new BusinessRuleException("Payment platform unavailable: " + exception.getMessage());
    }
  }

  @Retry(name = "paymentPlatform")
  @CircuitBreaker(name = "paymentPlatform")
  @Bulkhead(name = "paymentPlatform")
  public CloseResult close(PaymentView payment) {
    try {
      PaymentChannel channel = PaymentChannel.valueOf(payment.getChannel());
      String path = "/api/channel/transactions/" + payment.getChannelTransactionNo() + "/close";
      String body = "";
      var credentials = PaymentSigning.credentials(channel);
      return client.post().uri(baseUrl + path)
        .headers(headers -> PaymentSigning.apply(headers, "POST", path, body,
          credentials.appId(), credentials.secret()))
        .retrieve().body(CloseResult.class);
    } catch (Exception exception) {
      throw new BusinessRuleException("Could not close pending payment " + payment.getPaymentNumber()
        + ": " + exception.getMessage());
    }
  }

  public record Result(String channelTransactionNo, String status, String actionType,
                       String checkoutUrl, String qrPayload, java.time.LocalDateTime expiresAt) {
    public java.time.Instant expiresInstant() {
      return expiresAt.toInstant(java.time.ZoneOffset.UTC);
    }
  }

  public record CloseResult(String channelTransactionNo, String status) {
  }
}
