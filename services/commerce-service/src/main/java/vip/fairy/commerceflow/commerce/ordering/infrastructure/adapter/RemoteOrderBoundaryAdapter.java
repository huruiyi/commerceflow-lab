package vip.fairy.commerceflow.commerce.ordering.infrastructure.adapter;

import vip.fairy.commerceflow.commerce.ordering.application.port.OrderFulfillmentPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderLogisticsPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderPaymentPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderReceiptPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderRefundPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class RemoteOrderBoundaryAdapter implements OrderFulfillmentPort, OrderLogisticsPort,
  OrderPaymentPort, OrderReceiptPort, OrderRefundPort {

  private final OrderStorePort orders;

  public RemoteOrderBoundaryAdapter(OrderStorePort orders) {
    this.orders = orders;
  }

  @Override
  public List<OrderItemView> items(Long orderId) {
    return orders.items(orderId);
  }

  @Override
  public OrderAddressView address(Long orderId) {
    return orders.address(orderId);
  }

  @Override
  public List<OrderEventView> events(Long orderId) {
    return orders.events(orderId);
  }

  @Override
  public boolean belongsToCustomer(Long orderId, Long customerId) {
    return orders.find(customerId, orderId).isPresent();
  }

  @Override
  public void statusUpdated(Long orderId, String logisticsStatus, Instant occurredAt,
                            String detail, String triggerSource) {
    if ("DELIVERED".equals(logisticsStatus)) {
      orders.markLogisticsDelivered(orderId, occurredAt);
    }
    String currentStatus = orders.status(orderId);
    orders.insertEventDetailed(orderId, null, "LOGISTICS_STATUS_UPDATED",
      currentStatus, currentStatus, detail, triggerSource, null);
  }

  @Override
  public ShopOrderView lockForPaymentResult(Long orderId) {
    return orders.lockById(orderId).orElseThrow();
  }

  @Override
  public boolean paidFromPending(Long orderId) {
    return orders.updateStatus(orderId, "PENDING_PAYMENT", "PAID") == 1;
  }

  @Override
  public boolean paidAfterCancellationRace(Long orderId) {
    return orders.restorePaidAfterCancellationRace(orderId) == 1;
  }

  @Override
  public boolean paidAfterLateSuccess(Long orderId) {
    return orders.restorePaidAfterLateSuccess(orderId) == 1;
  }

  @Override
  public boolean startInventoryCommit(Long orderId) {
    return orders.startInventoryCommit(orderId) == 1;
  }

  @Override
  public boolean moveInventory(Long orderId, String fromStatus, String toStatus) {
    return orders.updateInventoryStatus(orderId, fromStatus, toStatus) == 1;
  }

  @Override
  public void resultApplied(Long orderId, Long customerId, String eventType,
                            String fromStatus, String detail) {
    orders.insertEventDetailed(orderId, customerId, eventType, fromStatus, "PAID", detail,
      "PAYMENT_CALLBACK", null);
  }

  @Override
  public Optional<ShopOrderView> lock(Long customerId, Long orderId) {
    return orders.lock(customerId, orderId);
  }

  @Override
  public Optional<ShopOrderView> lock(Long orderId) {
    return orders.lockById(orderId);
  }

  @Override
  public boolean received(Long orderId, Instant receivedAt) {
    return orders.markReceived(orderId, receivedAt) == 1;
  }

  @Override
  public boolean completed(Long orderId, Instant completedAt) {
    return orders.markCompleted(orderId, completedAt) == 1;
  }

  @Override
  public void transitionRecorded(Long orderId, Long actorId, String eventType,
                                 String fromStatus, String toStatus, String detail,
                                 String triggerSource, Long scheduledTaskId) {
    orders.insertEventDetailed(orderId, actorId, eventType, fromStatus, toStatus, detail,
      triggerSource, scheduledTaskId);
  }

  @Override
  public Optional<ShopOrderView> find(Long customerId, Long orderId) {
    return orders.find(customerId, orderId);
  }

  @Override
  public boolean start(Long orderId, String fromStatus) {
    return orders.updateStatus(orderId, fromStatus, "REFUNDING") == 1;
  }

  @Override
  public boolean complete(Long orderId) {
    return orders.updateStatus(orderId, "REFUNDING", "REFUNDED") == 1;
  }

  @Override
  public boolean restoreAfterFailure(Long orderId, String priorStatus) {
    return orders.updateStatus(orderId, "REFUNDING", priorStatus) == 1;
  }

  @Override
  public void resultRecorded(Long orderId, Long customerId, String eventType,
                             String fromStatus, String toStatus, String detail) {
    orders.insertEvent(orderId, customerId, eventType, fromStatus, toStatus, detail);
  }
}
