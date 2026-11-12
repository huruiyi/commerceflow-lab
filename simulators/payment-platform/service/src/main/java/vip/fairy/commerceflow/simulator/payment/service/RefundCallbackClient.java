package vip.fairy.commerceflow.simulator.payment.service;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelRefund;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RefundCallbackClient {
  private final RestClient client;
  private final ObjectMapper mapper;
  private final String url;
  private final String secret;

  public RefundCallbackClient(RestClient.Builder builder, ObjectMapper mapper, @Value("${payment-platform.refund-callback-url:http://localhost:18081/api/refund-notifications/callback}") String url, @Value("${payment-platform.callback-secret:demo-platform-callback-secret}") String secret) {
    this.client = builder.build();
    this.mapper = mapper;
    this.url = url;
    this.secret = secret;
  }

  public void send(ChannelRefund refund, String channel) {
    try {
      String body = mapper.writeValueAsString(Map.of("notificationId", UUID.randomUUID().toString(), "merchantRefundNo", refund.getMerchantRefundNo(), "channelRefundNo", refund.getChannelRefundNo(), "channel", channel, "status", refund.getStatus(), "amount", refund.getAmount(), "currency", refund.getCurrency(), "failureReason", refund.getFailureReason()));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String signature = Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
      client.post().uri(url).contentType(MediaType.APPLICATION_JSON).header("X-Callback-Signature", signature).body(body).retrieve().toBodilessEntity();
    } catch (Exception exception) {
      throw new IllegalStateException("Refund callback delivery failed", exception);
    }
  }
}
