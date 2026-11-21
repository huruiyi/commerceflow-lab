package vip.fairy.commerceflow.commerce.fulfillment.application;

import vip.fairy.commerceflow.commerce.fulfillment.api.MerchantOrderResponse;
import vip.fairy.commerceflow.commerce.fulfillment.application.contract.FulfillmentCommandV1;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsPackageQueryV1;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderFulfillmentPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundSnapshotV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class MerchantFulfillmentService {

  private final OrderStorePort orderStore;
  private final OrderFulfillmentPort orders;
  private final PaymentOrchestrationV1 payments;
  private final MerchantFulfillmentRoute route;
  private final LogisticsPackageQueryV1 logistics;

  public MerchantFulfillmentService(OrderStorePort orderStore,
                                    OrderFulfillmentPort orders,
                                    PaymentOrchestrationV1 payments,
                                    MerchantFulfillmentRoute route, LogisticsPackageQueryV1 logistics) {
    this.orderStore = orderStore;
    this.orders = orders;
    this.payments = payments;
    this.route = route;
    this.logistics = logistics;
  }

  public CommandResult submitCommand(Long merchantId, FulfillmentCommandV1 command) {
    if (command.merchantId() != null && !merchantId.equals(command.merchantId())) {
      throw new ResourceNotFoundException("Merchant order not found");
    }
    return route.submit(command.forMerchant(merchantId));
  }

  public CommandResult commandStatus(Long merchantId, String commandId) {
    return route.commandStatus(merchantId, commandId);
  }

  public record CommandResult(int version, String commandId, Long orderId, String operation, String step,
                              ShipmentJob.Status status, Long shipmentVersion, String errorCode) {
  }

  public List<MerchantOrderResponse> list(Long merchantId) {
    return orderStore.listMerchant(merchantId)
      .stream()
      .map(this::response)
      .toList();
  }

  public MerchantOrderResponse get(Long merchantId, Long id) {
    return listResponse(require(merchantId, id), merchantId);
  }

  public OrderStorePort.WarehousePlan warehousePlan(Long merchantId, Long id) {
    require(merchantId, id);
    if (orders.items(id).stream().anyMatch(item -> !merchantId.equals(item.getMerchantId()))) {
      return new OrderStorePort.WarehousePlan(List.of(), List.of());
    }
    return orderStore.warehousePlan(id);
  }

  public ShipmentStatus shipmentStatus(Long merchantId, Long id) {
    require(merchantId, id);
    ShipmentJob job = route.shipment(merchantId, id);
    if (job == null || !merchantId.equals(job.merchantId())) {
      throw new ResourceNotFoundException("Shipment operation not found");
    }
    return new ShipmentStatus(job.commandId(), job.step(), job.status(), job.lastError());
  }

  public record ShipmentStatus(String commandId, ShipmentJob.Step step, ShipmentJob.Status status,
                               String lastError) {
  }

  private ShopOrderView require(Long merchantId, Long id) {
    return orderStore.findMerchant(merchantId, id)
      .orElseThrow(() -> new ResourceNotFoundException("Merchant order not found"));
  }

  private MerchantOrderResponse response(ShopOrderView order) {
    return listResponse(order, null);
  }

  public MerchantOrderResponse listResponse(ShopOrderView order, Long merchantId) {
    var orderItems = orders.items(order.getId());
    var merchantItems = merchantId == null ? orderItems
      : orderItems.stream().filter(item -> merchantId.equals(item.getMerchantId())).toList();
    boolean sharedOrder = merchantItems.size() < orderItems.size();
    RefundSnapshotV1 refund = sharedOrder ? null : payments.refund(
      new PaymentQueryV1(PaymentQueryV1.VERSION, order.getId())).orElse(null);
    List<ShopOrderResponse.Item> items = merchantItems
      .stream()
      .map(ShopOrderResponse.Item::from)
      .toList();
    ShopOrderResponse.Address address = ShopOrderResponse.Address.from(orders.address(order.getId()));
    List<MerchantOrderResponse.Event> events = sharedOrder ? List.of() : orders.events(order.getId())
      .stream()
      .filter(event -> visibleEvent(event.getEventType(), event.getDetail()))
      .map(MerchantOrderResponse.Event::from)
      .toList();
    return new MerchantOrderResponse(
      order.getId(),
      order.getOrderNumber(),
      order.getStatus(),
      order.getInventoryStatus(),
      sharedOrder ? items.stream().map(ShopOrderResponse.Item::subtotal)
        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add) : order.getTotalAmount(),
      order.getCurrency(),
      order.getCreatedAt(),
      order.getConfirmedAt(),
      order.getShippedAt(),
      order.getCarrier(),
      order.getTrackingNumber(),
      order.getShipmentVersion(),
      items,
      address,
      events,
      sharedOrder ? List.of() : logistics.packageTracking(order.getId()),
      refund);
  }

  private boolean visibleEvent(String eventType, String detail) {
    if (!"SHIPMENT_UPDATED".equals(eventType) || detail == null) {
      return true;
    }
    String[] change = detail.split(" -> ", 2);
    return change.length != 2 || !change[0].trim().equals(change[1].trim());
  }
}
