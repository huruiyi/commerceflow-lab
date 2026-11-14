package vip.fairy.commerceflow.commerce.fulfillment.application;

import vip.fairy.commerceflow.commerce.fulfillment.api.FulfillmentRequest;
import vip.fairy.commerceflow.commerce.fulfillment.api.MerchantOrderResponse;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.MerchantOrderMapper;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsGatewayClient;
import vip.fairy.commerceflow.commerce.logistics.domain.ShipmentRecordView;
import vip.fairy.commerceflow.commerce.logistics.infrastructure.ShipmentMapper;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.ShopOrderMapper;
import vip.fairy.commerceflow.commerce.refund.api.RefundResponse;
import vip.fairy.commerceflow.commerce.refund.infrastructure.RefundMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Service
public class MerchantFulfillmentService {

  private final MerchantOrderMapper merchants;
  private final ShopOrderMapper orders;
  private final RefundMapper refunds;
  private final ShipmentMapper shipments;
  private final LogisticsGatewayClient logistics;
  private final TransactionTemplate transactions;

  public MerchantFulfillmentService(MerchantOrderMapper merchants,
                                    ShopOrderMapper orders,
                                    RefundMapper refunds,
                                    ShipmentMapper shipments,
                                    LogisticsGatewayClient logistics,
                                    PlatformTransactionManager manager) {
    this.merchants = merchants;
    this.orders = orders;
    this.refunds = refunds;
    this.shipments = shipments;
    this.logistics = logistics;
    this.transactions = new TransactionTemplate(manager);
  }

  public List<MerchantOrderResponse> list(AppUser merchant) {
    return merchants.list(merchant.getId())
      .stream()
      .map(this::response)
      .toList();
  }

  @Transactional
  public MerchantOrderResponse confirm(AppUser merchant, Long id) {
    require(merchant, id);
    if (merchants.confirm(id) != 1) {
      throw new BusinessRuleException("Only paid orders can be confirmed");
    }
    orders.insertEvent(id, merchant.getId(), "MERCHANT_CONFIRMED", "PAID", "CONFIRMED", "Merchant accepted the order");
    return current(merchant, id);
  }

  public MerchantOrderResponse ship(AppUser merchant, Long id, FulfillmentRequest request) {
    String carrier = request.carrier().trim();
    String tracking = request.trackingNumber().trim();
    ShopOrderView prepared = transactions.execute(status -> {
      ShopOrderView order = require(merchant, id);
      if ("SHIPPED".equals(order.getStatus())) {
        return order;
      }
      if (!"CONFIRMED".equals(order.getStatus())) {
        throw new BusinessRuleException("Only confirmed orders can be shipped");
      }
      shipments.prepare(id, "CFL-ORDER-" + id, carrier, tracking);
      return order;
    });
    LogisticsGatewayClient.Result remote = logistics.create(id, prepared.getOrderNumber(), carrier, tracking);
    transactions.executeWithoutResult(status -> {
      ShopOrderView order = require(merchant, id);
      ShipmentRecordView shipment = shipments.byOrder(id).orElseThrow();
      shipments.attach(shipment.getId(), remote.platformShipmentNo(), remote.carrier(),
        remote.trackingNumber(), remote.status());
      if ("CONFIRMED".equals(order.getStatus())) {
        if (merchants.ship(id, carrier, tracking) != 1) {
          throw new BusinessRuleException("Order status changed while shipping");
        }
        orders.insertEvent(id, merchant.getId(), "ORDER_SHIPPED", "CONFIRMED", "SHIPPED", carrier + " / " + tracking);
      }
    });
    return current(merchant, id);
  }

  public MerchantOrderResponse updateShipment(AppUser merchant, Long id, FulfillmentRequest request) {
    ShopOrderView current = require(merchant, id);
    if (!"SHIPPED".equals(current.getStatus())) {
      throw new BusinessRuleException("Only shipped orders can update shipment details");
    }
    String carrier = request.carrier().trim();
    String tracking = request.trackingNumber().trim();
    if (Objects.equals(current.getCarrier(), carrier)
      && Objects.equals(current.getTrackingNumber(), tracking)) {
      return response(current);
    }
    logistics.update(id, carrier, tracking);
    transactions.executeWithoutResult(status -> {
      if (merchants.updateShipment(id, carrier, tracking) != 1) {
        throw new BusinessRuleException("Only shipped orders can update shipment details");
      }
      shipments.updateDetails(id, carrier, tracking);
    });
    String before = shipmentDetail(current.getCarrier(), current.getTrackingNumber());
    String after = shipmentDetail(carrier, tracking);
    orders.insertEvent(id, merchant.getId(), "SHIPMENT_UPDATED", "SHIPPED", "SHIPPED", before + " -> " + after);
    return current(merchant, id);
  }

  private String shipmentDetail(String carrier, String tracking) {
    return carrier + " / " + tracking;
  }

  private MerchantOrderResponse current(AppUser merchant, Long id) {
    return response(merchants.lock(merchant.getId(), id).orElseThrow());
  }

  private ShopOrderView require(AppUser merchant, Long id) {
    return merchants.lock(merchant.getId(), id)
      .orElseThrow(() -> new ResourceNotFoundException("Merchant order not found"));
  }

  private MerchantOrderResponse response(ShopOrderView order) {
    RefundResponse refund = refunds.byOrder(order.getId())
      .map(value -> RefundResponse.from(value, refunds.events(value.getId())))
      .orElse(null);
    List<ShopOrderResponse.Item> items = orders.items(order.getId())
      .stream()
      .map(ShopOrderResponse.Item::from)
      .toList();
    ShopOrderResponse.Address address = ShopOrderResponse.Address.from(orders.address(order.getId()));
    List<MerchantOrderResponse.Event> events = orders.events(order.getId())
      .stream()
      .filter(this::visibleEvent)
      .map(MerchantOrderResponse.Event::from)
      .toList();
    return new MerchantOrderResponse(
      order.getId(),
      order.getOrderNumber(),
      order.getStatus(),
      order.getTotalAmount(),
      order.getCurrency(),
      order.getCreatedAt(),
      order.getConfirmedAt(),
      order.getShippedAt(),
      order.getCarrier(),
      order.getTrackingNumber(),
      items,
      address,
      events,
      refund);
  }

  private boolean visibleEvent(OrderEventView event) {
    if (!"SHIPMENT_UPDATED".equals(event.getEventType()) || event.getDetail() == null) {
      return true;
    }
    String[] change = event.getDetail().split(" -> ", 2);
    return change.length != 2 || !change[0].trim().equals(change[1].trim());
  }
}
