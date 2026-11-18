package vip.fairy.commerceflow.commerce.fulfillment.application;

import vip.fairy.commerceflow.commerce.fulfillment.api.FulfillmentRequest;
import vip.fairy.commerceflow.commerce.fulfillment.api.MerchantOrderResponse;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsGatewayClient;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsService;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderFulfillmentPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundSnapshotV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
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

  private final OrderStorePort orderStore;
  private final OrderFulfillmentPort orders;
  private final PaymentOrchestrationV1 payments;
  private final LogisticsService shipments;
  private final LogisticsGatewayClient logistics;
  private final TransactionTemplate transactions;

  public MerchantFulfillmentService(OrderStorePort orderStore,
                                    OrderFulfillmentPort orders,
                                    PaymentOrchestrationV1 payments,
                                    LogisticsService shipments,
                                    LogisticsGatewayClient logistics,
                                    PlatformTransactionManager manager) {
    this.orderStore = orderStore;
    this.orders = orders;
    this.payments = payments;
    this.shipments = shipments;
    this.logistics = logistics;
    this.transactions = new TransactionTemplate(manager);
  }

  public List<MerchantOrderResponse> list(Long merchantId) {
    return orderStore.listMerchant(merchantId)
      .stream()
      .map(this::response)
      .toList();
  }

  @Transactional
  public MerchantOrderResponse confirm(Long merchantId, Long id) {
    require(merchantId, id);
    if (orderStore.merchantConfirm(id, merchantId) != 1) {
      throw new BusinessRuleException("Only paid orders can be confirmed");
    }
    return current(merchantId, id);
  }

  public MerchantOrderResponse ship(Long merchantId, Long id, FulfillmentRequest request) {
    String carrier = request.carrier().trim();
    String tracking = request.trackingNumber().trim();
    ShopOrderView prepared = transactions.execute(status -> {
      ShopOrderView order = require(merchantId, id);
      if ("SHIPPED".equals(order.getStatus())) {
        return order;
      }
      if (!"CONFIRMED".equals(order.getStatus())) {
        throw new BusinessRuleException("Only confirmed orders can be shipped");
      }
      requireInventoryCommitted(order);
      shipments.prepareShipment(id, carrier, tracking);
      return order;
    });
    LogisticsGatewayClient.Result remote = logistics.create(id, prepared.getOrderNumber(), carrier, tracking);
    transactions.executeWithoutResult(status -> {
      ShopOrderView order = require(merchantId, id);
      shipments.attachShipment(id, remote);
      if ("CONFIRMED".equals(order.getStatus())) {
        if (orderStore.merchantShip(id, merchantId, carrier, tracking) != 1) {
          throw new BusinessRuleException("Order status changed while shipping");
        }
      }
    });
    return current(merchantId, id);
  }

  public MerchantOrderResponse updateShipment(Long merchantId, Long id, FulfillmentRequest request) {
    ShopOrderView current = require(merchantId, id);
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
      if (orderStore.updateShipment(id, carrier, tracking) != 1) {
        throw new BusinessRuleException("Only shipped orders can update shipment details");
      }
      shipments.updateShipmentDetails(id, carrier, tracking);
    });
    String before = shipmentDetail(current.getCarrier(), current.getTrackingNumber());
    String after = shipmentDetail(carrier, tracking);
    orders.shipmentUpdated(id, merchantId, before, after);
    return current(merchantId, id);
  }

  private String shipmentDetail(String carrier, String tracking) {
    return carrier + " / " + tracking;
  }

  private MerchantOrderResponse current(Long merchantId, Long id) {
    return response(require(merchantId, id));
  }

  private ShopOrderView require(Long merchantId, Long id) {
    return orderStore.findMerchant(merchantId, id)
      .orElseThrow(() -> new ResourceNotFoundException("Merchant order not found"));
  }

  private void requireInventoryCommitted(ShopOrderView order) {
    if (!"COMMITTED".equals(order.getInventoryStatus())) {
      throw new BusinessRuleException("Inventory confirmation is required before fulfillment");
    }
  }

  private MerchantOrderResponse response(ShopOrderView order) {
    RefundSnapshotV1 refund = payments.refund(
      new PaymentQueryV1(PaymentQueryV1.VERSION, order.getId())).orElse(null);
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
      order.getInventoryStatus(),
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
