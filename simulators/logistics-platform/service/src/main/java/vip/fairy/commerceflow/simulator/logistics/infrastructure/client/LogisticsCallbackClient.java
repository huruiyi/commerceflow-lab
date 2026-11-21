package vip.fairy.commerceflow.simulator.logistics.infrastructure.client;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vip.fairy.commerceflow.simulator.logistics.infrastructure.configuration.CallbackDeliveryProperties;
import vip.fairy.commerceflow.simulator.logistics.infrastructure.security.LogisticsCallbackSignature;

@Component
public class LogisticsCallbackClient {

  private final RestClient client;
  private final LogisticsCallbackSignature signatures;

  public LogisticsCallbackClient(RestClient.Builder builder,
                                 LogisticsCallbackSignature signatures,
                                 CallbackDeliveryProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.getConnectTimeout());
    requestFactory.setReadTimeout(properties.getReadTimeout());
    this.client = builder.requestFactory(requestFactory).build();
    this.signatures = signatures;
  }

  public void send(String callbackUrl, String body) {
    try {
      client.post().uri(callbackUrl).contentType(MediaType.APPLICATION_JSON)
        .header(LogisticsCallbackSignature.HEADER, signatures.sign(body)).body(body).retrieve().toBodilessEntity();
    } catch (Exception exception) {
      throw new IllegalStateException("Logistics callback delivery failed", exception);
    }
  }
}
