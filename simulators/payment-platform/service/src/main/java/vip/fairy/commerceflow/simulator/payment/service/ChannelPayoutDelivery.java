package vip.fairy.commerceflow.simulator.payment.service;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelPayout;
import vip.fairy.commerceflow.simulator.payment.mapper.ChannelPayoutMapper;

@Component
public class ChannelPayoutDelivery {

  private final ChannelPayoutMapper payouts;
  private final ObjectMapper mapper;
  private final RestClient client;
  private final String callbackUrl;
  private final String secret;

  public ChannelPayoutDelivery(ChannelPayoutMapper payouts, ObjectMapper mapper, RestClient.Builder builder,
    @Value("${PAYOUT_CALLBACK_URL:http://commerce-service:8080/api/payout-notifications}") String callbackUrl,
    @Value("${PAYOUT_CALLBACK_SECRET:demo-payout-callback-secret}") String secret) {
    this.payouts = payouts;
    this.mapper = mapper;
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var request = new JdkClientHttpRequestFactory(http);
    request.setReadTimeout(Duration.ofSeconds(3));
    this.client = builder.requestFactory(request).build();
    this.callbackUrl = callbackUrl;
    this.secret = secret;
  }

  @Scheduled(fixedDelayString = "${PAYOUT_SIMULATION_INTERVAL:2s}")
  public void dispatch() {
    payouts.pending().forEach(attempt -> payouts.resolve(attempt.attemptId()));
    payouts.callbacks().forEach(attempt -> deliver(attempt));
  }

  public boolean deliver(ChannelPayout attempt) {
    if ("PENDING".equals(attempt.status())) return false;
    boolean delivered = false;
    try {
      String raw = mapper.writeValueAsString(attempt);
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String signature = Base64.getEncoder().encodeToString(hmac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
      client.post().uri(callbackUrl).header("X-Payout-Signature", signature).contentType(MediaType.APPLICATION_JSON)
        .body(raw).retrieve().toBodilessEntity();
      delivered = true;
    } catch (Exception ignored) {
      // The terminal row remains eligible for callback redelivery.
    }
    payouts.notified(attempt.attemptId(), delivered);
    return delivered;
  }
}
