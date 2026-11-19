package vip.fairy.commerceflow.commerce.fulfillment.application;

import vip.fairy.commerceflow.commerce.fulfillment.api.FulfillmentRequest;
import vip.fairy.commerceflow.commerce.fulfillment.api.MerchantOrderResponse;
import vip.fairy.commerceflow.commerce.fulfillment.api.ShipmentUpdateRequest;
import vip.fairy.commerceflow.commerce.fulfillment.application.contract.FulfillmentCommandV1;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderFulfillmentPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundSnapshotV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import vip.fairy.commerceflow.commerce.fulfillment.application.contract.FulfillmentCommandV1.Operation;

@Service
public class MerchantFulfillmentService {

  private final OrderStorePort orderStore;
  private final OrderFulfillmentPort orders;
  private final PaymentOrchestrationV1 payments;
  private final MerchantFulfillmentRoute route;

  public MerchantFulfillmentService(OrderStorePort orderStore,
                                    OrderFulfillmentPort orders,
                                    PaymentOrchestrationV1 payments,
                                    MerchantFulfillmentRoute route) {
    this.orderStore = orderStore;
    this.orders = orders;
    this.payments = payments;
    this.route = route;
  }

  public CommandResult submitCommand(Long merchantId, FulfillmentCommandV1 command) {
    if (!merchantId.equals(command.merchantId())) throw new ResourceNotFoundException("Merchant order not found");
    return route.submit(command);
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

  public OperationResult confirm(Long merchantId, Long id) {
    ShopOrderView prepared = require(merchantId, id);
    if (!"PAID".equals(prepared.getStatus())) {
      throw new BusinessRuleException("Only paid orders can be confirmed");
    }
    return operationResult(merchantId, prepared, route.legacy(
      legacyCommand(Operation.CONFIRM, merchantId, id, null, null, null)));
  }

  public MerchantOrderResponse ship(Long merchantId, Long id, FulfillmentRequest request) {
    String carrier = request.carrier().trim();
    String tracking = request.trackingNumber().trim();
    ShopOrderView prepared = require(merchantId, id);
    if ("SHIPPED".equals(prepared.getStatus())) {
      return response(prepared);
    }
    if (!"CONFIRMED".equals(prepared.getStatus())) {
      throw new BusinessRuleException("Only confirmed orders can be shipped");
    }
    requireInventoryCommitted(prepared);
    FulfillmentCommandV1 command = legacyCommand(Operation.SHIP, merchantId, id, carrier, tracking, null);
    route.legacy(command);
    return current(merchantId, id);
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

  public OperationResult updateShipment(Long merchantId, Long id, ShipmentUpdateRequest request) {
    ShopOrderView current = require(merchantId, id);
    if (!"SHIPPED".equals(current.getStatus())) {
      throw new BusinessRuleException("Only shipped orders can update shipment details");
    }
    String carrier = request.carrier().trim();
    String tracking = request.trackingNumber().trim();
    long expected = request.expectedVersion();
    if (current.getShipmentVersion() != expected) {
      throw new BusinessRuleException("Shipment version conflict. Reload the order and try again");
    }
    if (Objects.equals(current.getCarrier(), carrier)
      && Objects.equals(current.getTrackingNumber(), tracking)) {
      return new OperationResult(response(current), null);
    }
    FulfillmentCommandV1 command = legacyCommand(Operation.UPDATE_SHIPMENT,
      merchantId, id, carrier, tracking, expected);
    return operationResult(merchantId, current,
      route.legacy(command));
  }

  public OperationStatus operationStatus(Long merchantId, Long id, String commandId) {
    require(merchantId, id);
    CommandResult job = route.commandStatus(merchantId, commandId);
    if (!id.equals(job.orderId()) || "SHIP".equals(job.operation())) {
      throw new ResourceNotFoundException("Fulfillment operation not found");
    }
    return new OperationStatus(job.commandId(), MerchantFulfillmentJob.Operation.valueOf(job.operation()),
      MerchantFulfillmentJob.Step.valueOf(job.step()), job.status(), job.errorCode());
  }

  private OperationResult operationResult(Long merchantId, ShopOrderView prepared, CommandResult job) {
    if (job.status() == ShipmentJob.Status.COMPLETED) {
      return new OperationResult(current(merchantId, job.orderId()), null);
    }
    return new OperationResult(response(prepared), job.commandId());
  }

  public record OperationResult(MerchantOrderResponse order, String pendingCommandId) {
  }

  public record OperationStatus(String commandId, MerchantFulfillmentJob.Operation operation,
                                MerchantFulfillmentJob.Step step, ShipmentJob.Status status, String lastError) {
  }

  private FulfillmentCommandV1 legacyCommand(Operation operation, Long merchantId, Long orderId,
                                             String carrier, String tracking, Long expectedVersion) {
    return new FulfillmentCommandV1(1, UUID.randomUUID(), operation, merchantId, orderId, Instant.now(),
      carrier, tracking, expectedVersion);
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
