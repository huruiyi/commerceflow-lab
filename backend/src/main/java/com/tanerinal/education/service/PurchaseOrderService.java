package com.tanerinal.education.service;

import com.tanerinal.education.domain.*;
import com.tanerinal.education.mapper.CartMapper;
import com.tanerinal.education.mapper.ShopOrderMapper;
import com.tanerinal.education.web.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class PurchaseOrderService {
  private final ShopOrderMapper orders; private final CartMapper carts;
  public PurchaseOrderService(ShopOrderMapper orders, CartMapper carts){this.orders=orders;this.carts=carts;}

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
    Optional<PaymentView> existing=orders.paymentByKey(request.idempotencyKey());
    if(existing.isPresent()){
      if(!existing.get().getOrderId().equals(id)) throw new BusinessRuleException("Idempotency key belongs to another order");
      return assemble(orders.find(user.getId(),id).orElseThrow(),existing.get());
    }
    if(!"PENDING_PAYMENT".equals(order.getStatus())) throw new BusinessRuleException("Only pending orders can be paid");
    String status=request.simulateFailure()?"FAILED":"SUCCESS";
    String reason=request.simulateFailure()?"Simulated payment decline":"";
    orders.insertPayment(id,number("PAY"),request.idempotencyKey(),order.getTotalAmount(),status,reason);
    if(request.simulateFailure()){
      orders.insertEvent(id,user.getId(),"PAYMENT_FAILED","PENDING_PAYMENT","PENDING_PAYMENT",reason);
    } else {
      if(orders.updateStatus(id,"PENDING_PAYMENT","PAID")!=1) throw new BusinessRuleException("Order status changed; payment was not applied");
      orders.insertEvent(id,user.getId(),"PAYMENT_SUCCEEDED","PENDING_PAYMENT","PAID","Simulated payment accepted");
    }
    PaymentView payment=orders.paymentByKey(request.idempotencyKey()).orElseThrow();
    return assemble(orders.find(user.getId(),id).orElseThrow(),payment);
  }

  private ShopOrderResponse assemble(ShopOrderView o,PaymentView payment){
    List<ShopOrderResponse.Item> items=orders.items(o.getId()).stream().map(ShopOrderResponse.Item::from).toList();
    PaymentView resolved=payment!=null?payment:orders.latestPayment(o.getId()).orElse(null);
    return new ShopOrderResponse(o.getId(),o.getOrderNumber(),o.getStatus(),o.getTotalAmount(),o.getCurrency(),o.getCreatedAt(),items,ShopOrderResponse.Address.from(orders.address(o.getId())),ShopOrderResponse.Payment.from(resolved));
  }
  private String number(String prefix){return prefix+"-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();}
}
