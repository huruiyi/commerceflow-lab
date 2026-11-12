package lab.commerceflow.storefront.service;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

@Component
public class LogisticsGatewayClient {
  private final RestClient client;private final ObjectMapper mapper;private final String baseUrl,appId,secret,callbackUrl;
  public LogisticsGatewayClient(RestClient.Builder builder,ObjectMapper mapper,
      @Value("${commerceflow.logistics-platform.base-url:http://localhost:18083}")String baseUrl,
      @Value("${commerceflow.logistics-platform.app-id:commerceflow-storefront}")String appId,
      @Value("${commerceflow.logistics-platform.request-secret:demo-logistics-request-secret}")String secret,
      @Value("${commerceflow.logistics-platform.callback-url:http://localhost:18081/api/logistics-notifications/callback}")String callbackUrl){this.client=builder.build();this.mapper=mapper;this.baseUrl=baseUrl;this.appId=appId;this.secret=secret;this.callbackUrl=callbackUrl;}
  public Result create(Long orderId,String orderNumber,String carrier,String trackingNumber){
    try{
      String body=mapper.writeValueAsString(Map.of("merchantShipmentNo","CFL-ORDER-"+orderId,"orderNumber",orderNumber,"carrier",carrier,"trackingNumber",trackingNumber,"callbackUrl",callbackUrl));
      Headers headers=sign(body);
      return client.post().uri(baseUrl+"/api/channel/shipments").contentType(MediaType.APPLICATION_JSON)
          .header("X-App-Id",appId).header("X-Timestamp",headers.timestamp()).header("X-Nonce",headers.nonce()).header("X-Signature",headers.signature())
          .body(body).retrieve().body(Result.class);
    }catch(Exception exception){throw new BusinessRuleException("Logistics platform is unavailable: "+exception.getMessage());}
  }
  public Result update(Long orderId,String carrier,String trackingNumber){
    try{
      String body=mapper.writeValueAsString(Map.of("carrier",carrier,"trackingNumber",trackingNumber));Headers headers=sign(body);
      return client.put().uri(baseUrl+"/api/channel/shipments/CFL-ORDER-"+orderId).contentType(MediaType.APPLICATION_JSON)
          .header("X-App-Id",appId).header("X-Timestamp",headers.timestamp()).header("X-Nonce",headers.nonce()).header("X-Signature",headers.signature())
          .body(body).retrieve().body(Result.class);
    }catch(Exception exception){throw new BusinessRuleException("Logistics platform is unavailable: "+exception.getMessage());}
  }
  public Result query(String merchantShipmentNo){
    try{
      Headers headers=sign("");
      String encoded=UriUtils.encodeQueryParam(merchantShipmentNo,StandardCharsets.UTF_8);
      return client.get().uri(baseUrl+"/api/channel/shipments?merchantShipmentNo="+encoded)
          .header("X-App-Id",appId).header("X-Timestamp",headers.timestamp()).header("X-Nonce",headers.nonce()).header("X-Signature",headers.signature())
          .retrieve().body(Result.class);
    }catch(Exception exception){throw new BusinessRuleException("Logistics platform query failed: "+exception.getMessage());}
  }
  public List<CallbackOutbox> callbackOutbox(String status,int limit){
    try{
      Headers headers=sign("");
      String query="?limit="+Math.max(1,Math.min(limit,500));
      if(status!=null&&!status.isBlank())query+="&status="+UriUtils.encodeQueryParam(status,StandardCharsets.UTF_8);
      CallbackOutbox[] values=client.get().uri(baseUrl+"/api/channel/callback-outbox"+query)
          .header("X-App-Id",appId).header("X-Timestamp",headers.timestamp()).header("X-Nonce",headers.nonce()).header("X-Signature",headers.signature())
          .retrieve().body(CallbackOutbox[].class);
      return values==null?List.of():List.of(values);
    }catch(Exception exception){throw new BusinessRuleException("Logistics callback outbox query failed: "+exception.getMessage());}
  }
  public List<CallbackAuditEvent> callbackEvents(Long id){
    try{
      Headers headers=sign("");
      CallbackAuditEvent[] values=client.get().uri(baseUrl+"/api/channel/callback-outbox/"+id+"/events")
          .header("X-App-Id",appId).header("X-Timestamp",headers.timestamp()).header("X-Nonce",headers.nonce()).header("X-Signature",headers.signature())
          .retrieve().body(CallbackAuditEvent[].class);
      return values==null?List.of():List.of(values);
    }catch(Exception exception){throw new BusinessRuleException("Logistics callback audit query failed: "+exception.getMessage());}
  }
  public CallbackOutbox replayCallback(Long id,String operatedBy,String reason){
    try{
      String body=mapper.writeValueAsString(Map.of("operatedBy",operatedBy,"reason",reason==null?"":reason));
      Headers headers=sign(body);
      return client.post().uri(baseUrl+"/api/channel/callback-outbox/"+id+"/replay").contentType(MediaType.APPLICATION_JSON)
          .header("X-App-Id",appId).header("X-Timestamp",headers.timestamp()).header("X-Nonce",headers.nonce()).header("X-Signature",headers.signature())
          .body(body).retrieve().body(CallbackOutbox.class);
    }catch(RestClientResponseException exception){
      throw new ResponseStatusException(exception.getStatusCode(),
          "Logistics callback replay rejected: "+exception.getResponseBodyAsString(),exception);
    }catch(Exception exception){throw new BusinessRuleException("Logistics callback replay failed: "+exception.getMessage());}
  }
  private Headers sign(String body)throws Exception{
    String timestamp=Long.toString(Instant.now().getEpochSecond());String nonce=UUID.randomUUID().toString();
    String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    String canonical=appId+"\n"+timestamp+"\n"+nonce+"\n"+digest;Mac mac=Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    return new Headers(timestamp,nonce,Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
  }
  private record Headers(String timestamp,String nonce,String signature){}
  public record Result(Long id,String merchantShipmentNo,String platformShipmentNo,String orderNumber,String carrier,String trackingNumber,String status,String callbackUrl,Instant createdAt,Instant updatedAt,java.util.List<Event> events){}
  public record Event(String eventId,String eventType,String status,String location,String description,Instant occurredAt){}
  public record CallbackOutbox(Long id,Long shipmentId,String eventId,String callbackUrl,String status,
      int attempts,int maxAttempts,Instant nextAttemptAt,Instant leaseUntil,String claimedBy,
      String lastError,Instant createdAt,Instant publishedAt,Instant updatedAt){}
  public record CallbackAuditEvent(Long id,String eventType,String fromStatus,String toStatus,
      String operatedBy,String reason,Instant createdAt){}
}
