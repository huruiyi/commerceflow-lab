package lab.commerceflow.logistics;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LogisticsCallbackClient {
  private final RestClient client;private final String secret;
  public LogisticsCallbackClient(RestClient.Builder builder,
      @Value("${logistics-platform.callback-secret}")String secret,CallbackDeliveryProperties properties){
    SimpleClientHttpRequestFactory requestFactory=new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.getConnectTimeout());
    requestFactory.setReadTimeout(properties.getReadTimeout());
    this.client=builder.requestFactory(requestFactory).build();this.secret=secret;}
  public void send(String callbackUrl,String body){
    try{
      Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
      String signature=Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
      client.post().uri(callbackUrl).contentType(MediaType.APPLICATION_JSON)
          .header("X-Callback-Signature",signature).body(body).retrieve().toBodilessEntity();
    }catch(Exception exception){throw new IllegalStateException("Logistics callback delivery failed",exception);}
  }
}
