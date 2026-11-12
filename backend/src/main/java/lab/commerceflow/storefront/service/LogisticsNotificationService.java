package lab.commerceflow.storefront.service;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lab.commerceflow.storefront.domain.ShipmentRecordView;
import lab.commerceflow.storefront.mapper.ShipmentMapper;
import lab.commerceflow.storefront.mapper.ShopOrderMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LogisticsNotificationService {
  private static final List<String> FLOW=List.of("REQUESTED","CREATED","PICKED_UP","IN_TRANSIT","AT_STATION","OUT_FOR_DELIVERY","DELIVERED");
  private static final List<String> EVENT_TYPES=List.of("PROGRESS","EXCEPTION","RECOVERY");
  private final ShipmentMapper shipments;private final ShopOrderMapper orders;private final ObjectMapper mapper;private final String secret;private final OrderReceiptLifecycleService lifecycle;
  public LogisticsNotificationService(ShipmentMapper shipments,ShopOrderMapper orders,ObjectMapper mapper,
      @Value("${commerceflow.logistics-platform.callback-secret:demo-logistics-callback-secret}")String secret,
      OrderReceiptLifecycleService lifecycle){this.shipments=shipments;this.orders=orders;this.mapper=mapper;this.secret=secret;this.lifecycle=lifecycle;}
  @Transactional public Result accept(String raw,String signature){
    if(!valid(raw,signature))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"INVALID_CALLBACK_SIGNATURE");
    try{
      Notice notice=mapper.readValue(raw,Notice.class);
      return apply(notice,digest(raw),"LOGISTICS_CALLBACK","PROCESSED");
    }catch(ResponseStatusException exception){throw exception;}catch(Exception exception){throw new IllegalArgumentException("Invalid logistics callback",exception);}
  }
  @Transactional public ReconciliationResult reconcile(LogisticsGatewayClient.Result authoritative){
    int processed=0;int duplicates=0;int rejected=0;
    for(LogisticsGatewayClient.Event event:authoritative.events()){
      Notice notice=new Notice(event.eventId(),authoritative.merchantShipmentNo(),authoritative.platformShipmentNo(),
          event.eventType(),event.status(),authoritative.carrier(),authoritative.trackingNumber(),event.location(),
          event.description(),event.occurredAt());
      Result result=apply(notice,digest(event.eventId()),"LOGISTICS_RECONCILIATION","RECONCILED");
      if("PROCESSED".equals(result.status()))processed++;
      else if("DUPLICATE".equals(result.status()))duplicates++;
      else rejected++;
    }
    return new ReconciliationResult(processed,duplicates,rejected);
  }
  private Result apply(Notice notice,String digest,String source,String processedStatus){
    if(shipments.notificationCount(notice.notificationId())>0)return new Result("DUPLICATE");
    ShipmentRecordView shipment=shipments.lockByMerchantNo(notice.merchantShipmentNo()).orElse(null);
    if(shipment==null)return new Result("UNKNOWN_SHIPMENT");
    if(shipment.getPlatformShipmentNo()!=null&&notice.platformShipmentNo()!=null
        &&!shipment.getPlatformShipmentNo().equals(notice.platformShipmentNo())){
      shipments.insertNotification(shipment.getId(),notice.notificationId(),"REJECTED",digest,"Platform shipment mismatch");return new Result("REJECTED");
    }
    String eventType=notice.eventType()==null?"PROGRESS":notice.eventType();
    if(!EVENT_TYPES.contains(eventType)||(!FLOW.contains(notice.status())&&!"EXCEPTION".equals(notice.status()))){
      shipments.insertNotification(shipment.getId(),notice.notificationId(),"REJECTED",digest,"Unsupported event type or status");return new Result("REJECTED");
    }
    int inserted=shipments.insertEvent(shipment.getId(),notice.notificationId(),eventType,notice.status(),notice.location(),notice.description(),notice.occurredAt());
    if(inserted==1&&shouldApply(shipment.getStatus(),notice.status(),eventType)){
      shipments.updateStatus(shipment.getId(),notice.status(),notice.occurredAt());
      if("DELIVERED".equals(notice.status())){orders.markLogisticsDelivered(shipment.getOrderId(),notice.occurredAt());lifecycle.scheduleAutoReceive(shipment.getOrderId(),notice.occurredAt());}
      orders.insertEventDetailed(shipment.getOrderId(),null,"LOGISTICS_STATUS_UPDATED",orders.status(shipment.getOrderId()),orders.status(shipment.getOrderId()),eventType+" · "+notice.status()+" · "+notice.location()+" · "+notice.description(),source,null);
    }
    shipments.insertNotification(shipment.getId(),notice.notificationId(),inserted==1?processedStatus:"DUPLICATE",digest,"");
    return new Result(inserted==1?"PROCESSED":"DUPLICATE");
  }
  private boolean shouldApply(String current,String incoming,String eventType){
    if("EXCEPTION".equals(eventType))return "EXCEPTION".equals(incoming)&&!"DELIVERED".equals(current);
    if("RECOVERY".equals(eventType))return "EXCEPTION".equals(current)&&FLOW.contains(incoming)&&!"DELIVERED".equals(incoming);
    return !"EXCEPTION".equals(current)&&rank(incoming)>=rank(current);
  }
  private int rank(String status){int value=FLOW.indexOf(status);return value<0?-1:value;}
  private String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception exception){throw new IllegalStateException("SHA-256 is unavailable",exception);}}
  private boolean valid(String raw,String signature){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return signature!=null&&MessageDigest.isEqual(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)),Base64.getDecoder().decode(signature));}catch(Exception exception){return false;}}
  public record Notice(String notificationId,String merchantShipmentNo,String platformShipmentNo,String eventType,String status,String carrier,String trackingNumber,String location,String description,Instant occurredAt){}
  public record Result(String status){}
  public record ReconciliationResult(int processed,int duplicates,int rejected){}
}
