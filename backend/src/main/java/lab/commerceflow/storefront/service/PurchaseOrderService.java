package lab.commerceflow.storefront.service;

import lab.commerceflow.storefront.domain.*;
import lab.commerceflow.storefront.mapper.CartMapper;
import lab.commerceflow.storefront.mapper.ShopOrderMapper;
import lab.commerceflow.storefront.mapper.RefundMapper;
import lab.commerceflow.storefront.web.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class PurchaseOrderService {
  private final ShopOrderMapper orders; private final CartMapper carts;private final PaymentGatewayClient gateway;private final RedisIdempotencyService redisIdempotency;private final RefundMapper refunds;
  public PurchaseOrderService(ShopOrderMapper orders, CartMapper carts,PaymentGatewayClient gateway,RedisIdempotencyService redisIdempotency,RefundMapper refunds){this.orders=orders;this.carts=carts;this.gateway=gateway;this.redisIdempotency=redisIdempotency;this.refunds=refunds;}

  @Transactional
  public ShopOrderResponse checkout(AppUser user) {
    List<CheckoutCartItem> items=orders.lockCartItems(user.getId());
    if(items.isEmpty()) throw new BusinessRuleException("Cart is empty");
    ShippingAddress address=orders.selectedAddress(user.getId());
    if(address==null) throw new BusinessRuleException("Select a valid shipping address before checkout");
    if(items.stream().anyMatch(i->i.getMerchantId()==null)) throw new BusinessRuleException("A cart product has no merchant");
    BigDecimal total=items.stream().map(CheckoutCartItem::subtotal).reduce(BigDecimal.ZERO,BigDecimal::add);
    NewShopOrder order=new NewShopOrder(number("ORD"),user.getId(),total,"CNY");
    orders.insertOrder(order);
    items.forEach(i->orders.insertItem(order.getId(),i,i.subtotal()));
    orders.insertAddress(order.getId(),address);
    orders.insertEvent(order.getId(),user.getId(),"ORDER_CREATED","CART","PENDING_PAYMENT","Checkout created from cart");
    carts.clear(user.getId());
    return require(user,order.getId());
  }

  public List<ShopOrderResponse> list(AppUser user){return orders.list(user.getId()).stream().map(o->assemble(o,null)).toList();}
  public ShopOrderResponse require(AppUser user,Long id){return orders.find(user.getId(),id).map(o->assemble(o,null)).orElseThrow(()->new ResourceNotFoundException("Order not found"));}

  @Transactional
  public ShopOrderResponse pay(AppUser user,Long id,PaymentRequest request){
    ShopOrderView order=orders.lock(user.getId(),id).orElseThrow(()->new ResourceNotFoundException("Order not found"));
    PaymentMerchantAccount merchant=orders.merchantAccount(request.channel())
        .orElseThrow(()->new BusinessRuleException("Payment channel is disabled"));
    String fingerprint=fingerprint(user.getId(),order,request);
    orders.ensurePaymentIdempotencyKey(request.idempotencyKey());
    orders.lockPaymentIdempotencyKey(request.idempotencyKey());
    Optional<PaymentView> existing=orders.paymentByKey(request.idempotencyKey());
    if(existing.isPresent()){
      PaymentView payment=existing.get();
      if(!payment.getOrderId().equals(id)
          || !merchant.getId().equals(payment.getMerchantAccountId())
          || !fingerprint.equals(payment.getRequestFingerprint())) {
        throw new BusinessRuleException("Idempotency key was already used with different payment parameters");
      }
      redisIdempotency.rememberPayment(request.idempotencyKey(),payment.getPaymentNumber());
      return assemble(orders.find(user.getId(),id).orElseThrow(),existing.get());
    }
    if(!"PENDING_PAYMENT".equals(order.getStatus())) throw new BusinessRuleException("Only pending orders can be paid");
    String status="PENDING";
    String reason="";
    Instant now=Instant.now();
    String paymentNumber=number("PAY");
    PaymentGatewayClient.Result remote=gateway.create(paymentNumber,request.channel(),"Order "+order.getOrderNumber(),order.getTotalAmount(),order.getCurrency(),"NONE");
    orders.insertPayment(id,merchant.getId(),number("PAY"),request.idempotencyKey(),request.channel(),
        order.getCurrency(),fingerprint,remote.channelTransactionNo(),order.getTotalAmount(),status,reason,null,remote.expiresInstant());
    PaymentView payment=orders.paymentByKey(request.idempotencyKey()).orElseThrow();
    orders.updateCheckout(payment.getId(),remote.checkoutUrl(),remote.actionType(),remote.qrPayload());
    orders.insertPaymentEvent(payment.getId(),UUID.randomUUID().toString(),"PAYMENT_PENDING","CREATED",status,"Waiting for signed asynchronous notification");
    redisIdempotency.rememberPayment(request.idempotencyKey(),payment.getPaymentNumber());
    return assemble(orders.find(user.getId(),id).orElseThrow(),orders.paymentByKey(request.idempotencyKey()).orElseThrow());
  }

  private ShopOrderResponse assemble(ShopOrderView o,PaymentView payment){
    List<ShopOrderResponse.Item> items=orders.items(o.getId()).stream().map(ShopOrderResponse.Item::from).toList();
    PaymentView resolved=payment!=null?payment:orders.latestPayment(o.getId()).orElse(null);
    RefundResponse refund=refunds.byOrder(o.getId()).map(value->RefundResponse.from(value,refunds.events(value.getId()))).orElse(null);
    return new ShopOrderResponse(o.getId(),o.getOrderNumber(),o.getStatus(),o.getTotalAmount(),o.getCurrency(),o.getCreatedAt(),items,ShopOrderResponse.Address.from(orders.address(o.getId())),ShopOrderResponse.Payment.from(resolved),refund);
  }
  private String number(String prefix){return prefix+"-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();}

  private String fingerprint(Long customerId,ShopOrderView order,PaymentRequest request){
    String value=customerId+"|"+order.getId()+"|"+request.channel()+"|"
        +order.getTotalAmount().stripTrailingZeros().toPlainString()+"|"+order.getCurrency()+"|"+request.simulateFailure();
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime",exception);
    }
  }
}
