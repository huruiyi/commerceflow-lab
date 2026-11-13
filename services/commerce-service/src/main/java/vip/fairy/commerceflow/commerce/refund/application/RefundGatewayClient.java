package vip.fairy.commerceflow.commerce.refund.application;

import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import vip.fairy.commerceflow.commerce.refund.domain.RefundView;
import vip.fairy.commerceflow.commerce.payment.infrastructure.PaymentSigning;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RefundGatewayClient {
  private final RestClient client;
  private final ObjectMapper mapper;
  private final String baseUrl;

  public RefundGatewayClient(
    RestClient.Builder builder,
    ObjectMapper mapper,
    @Value("${commerceflow.payment-platform.base-url:http://localhost:18082}") String baseUrl
  ) {
    this.client = builder.build();
    this.mapper = mapper;
    this.baseUrl = baseUrl;
  }

  public Result create(RefundView refund, PaymentView payment) {
    try {
      String body = mapper.writeValueAsString(Map.of(
        "merchantRefundNo", refund.getRefundNumber(),
        "channelTransactionNo", payment.getChannelTransactionNo(),
        "amount", refund.getAmount(),
        "currency", refund.getCurrency(),
        "reasonCode", refund.getReasonCode(),
        "faultScenario", "NONE"
      ));
      String path = "/api/channel/refunds";
      PaymentChannel channel = PaymentChannel.valueOf(payment.getChannel());
      var credentials = PaymentSigning.credentials(channel);
      return client.post()
        .uri(baseUrl + path)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> PaymentSigning.apply(
          headers, "POST", path, body, credentials.appId(), credentials.secret()))
        .body(body)
        .retrieve()
        .body(Result.class);
    } catch (Exception exception) {
      throw new BusinessRuleException("Refund platform unavailable: " + exception.getMessage());
    }
  }

  public void requestNotificationRedelivery(RefundView refund, PaymentView payment) {
    try {
      String path = "/api/channel/refunds/" + refund.getRefundNumber() + "/notify";
      PaymentChannel channel = PaymentChannel.valueOf(payment.getChannel());
      var credentials = PaymentSigning.credentials(channel);
      client.post()
        .uri(baseUrl + path)
        .headers(headers -> PaymentSigning.apply(
          headers, "POST", path, "", credentials.appId(), credentials.secret()))
        .retrieve()
        .toBodilessEntity();
    } catch (Exception exception) {
      throw new IllegalStateException("Refund reconciliation unavailable", exception);
    }
  }

  public record Result(String channelRefundNo, String merchantRefundNo, String status, String failureReason) {
  }
}
