package vip.fairy.commerceflow.commerce.ordering.application;

import vip.fairy.commerceflow.commerce.cart.domain.CheckoutAddressSnapshot;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.cart.application.port.CheckoutCartPort;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsService;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.ordering.api.CreatePaymentRequest;
import vip.fairy.commerceflow.commerce.ordering.domain.NewOrder;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.OrderLifecycleProperties;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCreateCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentSnapshotV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentWriteAdmissionV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundSnapshotV1;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskService;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskTypes;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PurchaseOrderService {

  private final OrderStorePort orders;
  private final CheckoutCartPort carts;
  private final PaymentOrchestrationV1 payments;
  private final PaymentWriteAdmissionV1 paymentWrites;
  private final OrderLifecycleProperties lifecycle;
  private final ScheduledTaskService scheduledTasks;
  private final LogisticsService logistics;
  private final InventoryCommandPort inventory;

  public PurchaseOrderService(OrderStorePort orders, CheckoutCartPort carts,
                              PaymentOrchestrationV1 payments,
                              PaymentWriteAdmissionV1 paymentWrites,
                              OrderLifecycleProperties lifecycle, ScheduledTaskService scheduledTasks,
                              LogisticsService logistics,
                              InventoryCommandPort inventory) {
    this.orders = orders;
    this.carts = carts;
    this.payments = payments;
    this.paymentWrites = paymentWrites;
    this.lifecycle = lifecycle;
    this.scheduledTasks = scheduledTasks;
    this.logistics = logistics;
    this.inventory = inventory;
  }

  @Transactional
  public ShopOrderResponse checkout(AppUser user) {
    List<CheckoutCartItem> items = carts.lockItems(user.getId());
    if (items.isEmpty()) {
      throw new BusinessRuleException("Cart is empty");
    }
    CheckoutAddressSnapshot address = carts.selectedAddress(user.getId());
    if (address == null) {
      throw new BusinessRuleException("Select a valid shipping address before checkout");
    }
    if (items.stream().anyMatch(item -> item.getMerchantId() == null)) {
      throw new BusinessRuleException("A cart product has no merchant");
    }
    BigDecimal total = items.stream().map(CheckoutCartItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    Instant paymentDueAt = Instant.now().plus(lifecycle.getPaymentTimeout());
    NewOrder order = new NewOrder(number("ORD"), user.getId(), total, "CNY", paymentDueAt);
    List<OrderItemSnapshot> orderItems = items.stream().map(OrderItemSnapshot::from).toList();
    orders.insertOrder(order, orderItems, OrderAddressSnapshot.from(address), user.getId());
    inventory.handle(InventoryCommandPort.reserve(
      "order:" + order.getId() + ":reserve",
      order.getId(),
      items.stream()
        .map(item -> InventoryCommandPort.item(
          item.getProductId(), item.getProductName(), item.getQuantity()))
        .toList()
    ));
    scheduledTasks.schedule(ScheduledTaskTypes.PAYMENT_TIMEOUT, "order:" + order.getId(), "ORDER",
      order.getId().toString(), paymentDueAt, Map.of("orderId", order.getId()));
    carts.clear(user.getId());
    return require(user, order.getId());
  }

  public List<ShopOrderResponse> list(AppUser user) {
    return orders.list(user.getId())
      .stream()
      .map(order -> assemble(order, null))
      .toList();
  }

  public ShopOrderResponse require(AppUser user, Long id) {
    return orders.find(user.getId(), id)
      .map(order -> assemble(order, null))
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
  }

  public ShopOrderResponse pay(AppUser user, Long id, CreatePaymentRequest request) {
    paymentWrites.requireWritable();
    ShopOrderView order = orders.find(user.getId(), id)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    if (!"PENDING_PAYMENT".equals(order.getStatus())) {
      throw new BusinessRuleException("Only pending orders can be paid");
    }
    if (!order.getPaymentDueAt().isAfter(Instant.now())) {
      throw new BusinessRuleException("Order payment deadline has expired");
    }
    String commandId = UUID.randomUUID().toString();
    String correlationId = UUID.randomUUID().toString();
    PaymentCreateCommandV1 command = new PaymentCreateCommandV1(PaymentCreateCommandV1.VERSION,
      commandId, Instant.now(), "commerce-service-ordering", user.getId(), order.getId(),
      order.getOrderNumber(), request.channel(), order.getTotalAmount(), order.getCurrency(),
      order.getPaymentDueAt(), request.idempotencyKey(), request.simulateFailure(), correlationId,
      correlationId, traceparent());
    PaymentSnapshotV1 payment = payments.create(command);
    return assemble(orders.find(user.getId(), id).orElseThrow(), payment);
  }

  private ShopOrderResponse assemble(ShopOrderView order, PaymentSnapshotV1 payment) {
    List<ShopOrderResponse.Item> items = orders.items(order.getId())
      .stream()
      .map(ShopOrderResponse.Item::from)
      .toList();
    PaymentQueryV1 query = new PaymentQueryV1(PaymentQueryV1.VERSION, order.getId());
    PaymentSnapshotV1 resolved = payment != null ? payment : payments.latest(query).orElse(null);
    RefundSnapshotV1 refund = payments.refund(query).orElse(null);
    return new ShopOrderResponse(
      order.getId(),
      order.getOrderNumber(),
      order.getStatus(),
      order.getInventoryStatus(),
      order.getTotalAmount(),
      order.getCurrency(),
      order.getCreatedAt(),
      order.getPaymentDueAt(),
      order.getCancelledAt(),
      order.getCancelReason(),
      order.getReceivedAt(),
      order.getCompletedAt(),
      order.getLogisticsDeliveredAt(),
      availableActions(order, resolved, refund),
      items,
      ShopOrderResponse.Address.from(orders.address(order.getId())),
      ShopOrderResponse.Payment.from(resolved),
      logistics.find(order.getId()),
      refund);
  }

  private List<String> availableActions(ShopOrderView order, PaymentSnapshotV1 payment,
                                        RefundSnapshotV1 refund) {
    LinkedHashSet<String> actions = new LinkedHashSet<>();
    switch (order.getStatus()) {
      case "PENDING_PAYMENT" -> {
        actions.add("PAY");
        actions.add("REFRESH_PAYMENT");
        actions.add("CANCEL_ORDER");
      }
      case "PAID", "CONFIRMED" -> actions.add("REQUEST_REFUND");
      case "SHIPPED" -> {
        actions.add("VIEW_LOGISTICS");
        actions.add("COPY_TRACKING_NUMBER");
        actions.add("CONFIRM_RECEIPT");
      }
      case "RECEIVED", "COMPLETED" -> {
        actions.add("VIEW_LOGISTICS");
        actions.add("COPY_TRACKING_NUMBER");
        actions.add("REVIEW_ITEM");
      }
      default -> {
      }
    }
    if (payment != null) {
      actions.add("VIEW_PAYMENT");
    }
    if (refund != null) {
      actions.add("VIEW_REFUND");
    }
    return List.copyOf(actions);
  }

  private String number(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
  }

  private String traceparent() {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }
}
