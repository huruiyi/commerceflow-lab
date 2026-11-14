package vip.fairy.commerceflow.simulator.payment.service;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelTransaction;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentCallbackClient {

  private final RestClient client;
  private final ObjectMapper mapper;
  private final String url, secret;

  public PaymentCallbackClient(
    RestClient.Builder b,
    ObjectMapper m,
    @Value("${payment-platform.callback-url:http://localhost:18081/api/payment-notifications/callback}")
    String u,
    @Value("${payment-platform.callback-secret:demo-platform-callback-secret}") String s) {
    client = b.build();
    mapper = m;
    url = u;
    secret = s;
  }

  public void send(ChannelTransaction t) {
    try {
      String body = mapper.writeValueAsString(Map.of(
        "notificationId", UUID.randomUUID().toString(),
        "channelTransactionNo", t.getChannelTransactionNo(),
        "channel", t.getChannel(),
        "status", t.getStatus(),
        "amount", t.getAmount(),
        "currency", t.getCurrency(),
        "failureReason", "FAILED".equals(t.getStatus()) ? "Simulated decline" : ""));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String sig = Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
      client.post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Callback-Signature", sig)
        .body(body)
        .retrieve()
        .toBodilessEntity();
    } catch (Exception e) {
      throw new IllegalStateException("Callback delivery failed", e);
    }
  }
}
