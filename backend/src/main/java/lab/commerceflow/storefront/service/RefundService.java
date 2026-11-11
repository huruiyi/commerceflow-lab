package lab.commerceflow.storefront.service;

import lab.commerceflow.storefront.domain.*;
import lab.commerceflow.storefront.mapper.*;
import lab.commerceflow.storefront.web.dto.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundService {
  private final ShopOrderMapper orders; private final RefundMapper refunds; private final RefundGatewayClient gateway;
  private final PaymentEventPipeline events; private final TransactionTemplate transactions;
  public RefundService(ShopOrderMapper orders,RefundMapper refunds,RefundGatewayClient gateway,PaymentEventPipeline events,PlatformTransactionManager manager){this.orders=orders;this.refunds=refunds;this.gateway=gateway;this.events=events;this.transactions=new TransactionTemplate(manager);}

  public RefundResponse request(AppUser user,Long orderId,RefundRequest request){
    Prepared prepared=transactions.execute(status->prepare(user,orderId,request));
    if(prepared==null)throw new IllegalStateException("Refund transaction did not return a result");
    if(!prepared.created())return response(prepared.refund());
    try{
      RefundGatewayClient.Result remote=gateway.create(prepared.refund(),prepared.payment());
      transactions.executeWithoutResult(status->refunds.attachChannel(prepared.refund().getId(),remote.channelRefundNo()));
      return get(user,prepared.refund().getRefundNumber());
    }catch(BusinessRuleException uncertain){
      // 请求可能已经被渠道受理。保留 PROCESSING，由相同退款号的对账补偿确认最终结果。
      return response(refunds.byPayment(prepared.payment().getId()).orElse(prepared.refund()));
    }
  }

  public List<RefundResponse> forOrder(AppUser user,Long orderId){
    orders.find(user.getId(),orderId).orElseThrow(()->new ResourceNotFoundException("Order not found"));
    return refunds.byCustomerOrder(user.getId(),orderId).map(value->List.of(response(value))).orElseGet(List::of);
  }

  public RefundResponse get(AppUser user,String refundNumber){return response(refunds.byCustomerNumber(user.getId(),refundNumber).orElseThrow(()->new ResourceNotFoundException("Refund not found")));}

  public RefundResponse forMerchantOrder(AppUser merchant,Long orderId){
    orders.items(orderId).stream().filter(item->merchant.getId().equals(item.getMerchantId())).findAny().orElseThrow(()->new ResourceNotFoundException("Merchant order not found"));
    return refunds.byOrder(orderId).map(this::response).orElse(null);
  }

  private Prepared prepare(AppUser user,Long orderId,RefundRequest request){
    ShopOrderView order=orders.lock(user.getId(),orderId).orElseThrow(()->new ResourceNotFoundException("Order not found"));
    PaymentView payment=refunds.lockSuccessfulPayment(orderId).orElseThrow(()->new BusinessRuleException("Successful payment not found"));
    String fingerprint=fingerprint(user.getId(),order,payment,request);
    var existing=refunds.byPayment(payment.getId());
    if(existing.isPresent()){
      RefundView value=existing.get();
      if(!value.getIdempotencyKey().equals(request.idempotencyKey())||!value.getRequestFingerprint().equals(fingerprint))throw new BusinessRuleException("This payment already has a different refund request");
      return new Prepared(value,payment,false);
    }
    if(!List.of("PAID","CONFIRMED").contains(order.getStatus()))throw new BusinessRuleException("Only paid or confirmed orders can be refunded before shipment");
    if(orders.updateStatus(orderId,order.getStatus(),"REFUNDING")!=1)throw new BusinessRuleException("Order status changed while requesting refund");
    String number=number(); String detail=request.reasonDetail()==null?"":request.reasonDetail().trim();
    try{refunds.insert(number,orderId,payment.getId(),user.getId(),request.idempotencyKey(),fingerprint,payment.getAmount(),payment.getCurrency(),request.reasonCode(),detail,order.getStatus());}
    catch(DuplicateKeyException exception){RefundView concurrent=refunds.byPayment(payment.getId()).orElseThrow(()->exception);return new Prepared(concurrent,payment,false);}
    RefundView refund=refunds.byPayment(payment.getId()).orElseThrow();
    refunds.insertEvent(refund.getId(),UUID.randomUUID().toString(),"REFUND_REQUESTED","REQUESTED","PROCESSING",request.reasonCode());
    orders.insertEvent(orderId,user.getId(),"ORDER_REFUND_REQUESTED",order.getStatus(),"REFUNDING",request.reasonCode());
    events.append(refund.getId().toString(),"REFUND_REQUESTED","{\"refundNumber\":\""+number+"\"}");
    return new Prepared(refund,payment,true);
  }

  private RefundResponse response(RefundView value){return RefundResponse.from(value,refunds.events(value.getId()));}
  private String number(){return "REF-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();}
  private String fingerprint(Long customerId,ShopOrderView order,PaymentView payment,RefundRequest request){
    try{String value=customerId+"|"+order.getId()+"|"+payment.getId()+"|"+payment.getAmount().stripTrailingZeros().toPlainString()+"|"+payment.getCurrency()+"|"+request.reasonCode()+"|"+(request.reasonDetail()==null?"":request.reasonDetail().trim());return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    catch(Exception exception){throw new IllegalStateException(exception);}
  }
  private record Prepared(RefundView refund,PaymentView payment,boolean created){}
}
