package lab.commerceflow.storefront.service;
import lab.commerceflow.storefront.domain.*;import lab.commerceflow.storefront.mapper.*;import lab.commerceflow.storefront.web.dto.*;import java.util.*;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
@Service public class MerchantFulfillmentService{
 private final MerchantOrderMapper merchants;private final ShopOrderMapper orders;private final RefundMapper refunds;
 public MerchantFulfillmentService(MerchantOrderMapper merchants,ShopOrderMapper orders,RefundMapper refunds){this.merchants=merchants;this.orders=orders;this.refunds=refunds;}
 public List<MerchantOrderResponse> list(AppUser merchant){return merchants.list(merchant.getId()).stream().map(this::response).toList();}
 @Transactional public MerchantOrderResponse confirm(AppUser merchant,Long id){
  require(merchant,id);
  if(merchants.confirm(id)!=1)throw new BusinessRuleException("Only paid orders can be confirmed");
  orders.insertEvent(id,merchant.getId(),"MERCHANT_CONFIRMED","PAID","CONFIRMED","Merchant accepted the order");
  return current(merchant,id);
 }
 @Transactional public MerchantOrderResponse ship(AppUser merchant,Long id,FulfillmentRequest request){
  require(merchant,id);
  String carrier=request.carrier().trim();String tracking=request.trackingNumber().trim();
  if(merchants.ship(id,carrier,tracking)!=1)throw new BusinessRuleException("Only confirmed orders can be shipped");
  orders.insertEvent(id,merchant.getId(),"ORDER_SHIPPED","CONFIRMED","SHIPPED",carrier+" / "+tracking);
  return current(merchant,id);
 }
 @Transactional public MerchantOrderResponse updateShipment(AppUser merchant,Long id,FulfillmentRequest request){
  ShopOrderView current=require(merchant,id);
  if(!"SHIPPED".equals(current.getStatus()))throw new BusinessRuleException("Only shipped orders can update shipment details");
  String carrier=request.carrier().trim();String tracking=request.trackingNumber().trim();
  if(Objects.equals(current.getCarrier(),carrier)&&Objects.equals(current.getTrackingNumber(),tracking))return response(current);
  if(merchants.updateShipment(id,carrier,tracking)!=1)throw new BusinessRuleException("Only shipped orders can update shipment details");
  String before=shipmentDetail(current.getCarrier(),current.getTrackingNumber());
  String after=shipmentDetail(carrier,tracking);
  orders.insertEvent(id,merchant.getId(),"SHIPMENT_UPDATED","SHIPPED","SHIPPED",before+" -> "+after);
  return current(merchant,id);
 }
 private String shipmentDetail(String carrier,String tracking){return carrier+" / "+tracking;}
 private MerchantOrderResponse current(AppUser merchant,Long id){return response(merchants.lock(merchant.getId(),id).orElseThrow());}
 private ShopOrderView require(AppUser merchant,Long id){return merchants.lock(merchant.getId(),id).orElseThrow(()->new ResourceNotFoundException("Merchant order not found"));}
 private MerchantOrderResponse response(ShopOrderView order){
  RefundResponse refund=refunds.byOrder(order.getId()).map(value->RefundResponse.from(value,refunds.events(value.getId()))).orElse(null);
  return new MerchantOrderResponse(order.getId(),order.getOrderNumber(),order.getStatus(),order.getTotalAmount(),order.getCurrency(),order.getCreatedAt(),order.getConfirmedAt(),order.getShippedAt(),order.getCarrier(),order.getTrackingNumber(),orders.items(order.getId()).stream().map(ShopOrderResponse.Item::from).toList(),ShopOrderResponse.Address.from(orders.address(order.getId())),orders.events(order.getId()).stream().filter(this::visibleEvent).map(MerchantOrderResponse.Event::from).toList(),refund);
 }
 private boolean visibleEvent(OrderEventView event){
  if(!"SHIPMENT_UPDATED".equals(event.getEventType())||event.getDetail()==null)return true;
  String[] change=event.getDetail().split(" -> ",2);
  return change.length!=2||!change[0].trim().equals(change[1].trim());
 }
}
