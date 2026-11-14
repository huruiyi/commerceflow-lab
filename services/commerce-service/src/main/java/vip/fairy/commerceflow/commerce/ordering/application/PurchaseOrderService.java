package vip.fairy.commerceflow.commerce.ordering.application;

import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.cart.domain.NewShopOrder;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CartMapper;
import vip.fairy.commerceflow.commerce.customer.domain.ShippingAddress;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.inventory.application.InventoryService;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsService;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.OrderLifecycleProperties;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.ShopOrderMapper;
import vip.fairy.commerceflow.commerce.payment.api.PaymentRequest;
import vip.fairy.commerceflow.commerce.payment.application.PaymentGatewayClient;
import vip.fairy.commerceflow.commerce.payment.application.RedisIdempotencyService;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentMerchantAccount;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import vip.fairy.commerceflow.commerce.refund.api.RefundResponse;
import vip.fairy.commerceflow.commerce.refund.infrastructure.RefundMapper;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskService;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskTypes;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PurchaseOrderService {

  private final ShopOrderMapper orders;
  private final CartMapper carts;
  private final PaymentGatewayClient gateway;
  private final RedisIdempotencyService redisIdempotency;
  private final RefundMapper refunds;
  private final OrderLifecycleProperties lifecycle;
  private final ScheduledTaskService scheduledTasks;
  private final LogisticsService logistics;
  private final InventoryService inventory;

  public PurchaseOrderService(ShopOrderMapper orders, CartMapper carts, PaymentGatewayClient gateway,
                              RedisIdempotencyService redisIdempotency, RefundMapper refunds,
                              OrderLifecycleProperties lifecycle, ScheduledTaskService scheduledTasks,
                              LogisticsService logistics,
                              InventoryService inventory) {
    this.orders = orders;
    this.carts = carts;
    this.gateway = gateway;
    this.redisIdempotency = redisIdempotency;
    this.refunds = refunds;
    this.lifecycle = lifecycle;
    this.scheduledTasks = scheduledTasks;
    this.logistics = logistics;
    this.inventory = inventory;
  }

  @Transactional
  public ShopOrderResponse checkout(AppUser user) {
    List<CheckoutCartItem> items = orders.lockCartItems(user.getId());
    if (items.isEmpty()) {
      throw new BusinessRuleException("Cart is empty");
    }
    ShippingAddress address = orders.selectedAddress(user.getId());
    if (address == null) {
      throw new BusinessRuleException("Select a valid shipping address before checkout");
    }
    if (items.stream().anyMatch(item -> item.getMerchantId() == null)) {
      throw new BusinessRuleException("A cart product has no merchant");
    }
    BigDecimal total = items.stream().map(CheckoutCartItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    Instant paymentDueAt = Instant.now().plus(lifecycle.getPaymentTimeout());
    NewShopOrder order = new NewShopOrder(number("ORD"), user.getId(), total, "CNY", paymentDueAt);
    orders.insertOrder(order);
    items.forEach(item -> orders.insertItem(order.getId(), item, item.subtotal()));
    inventory.reserve(order.getId(), items);
    orders.insertAddress(order.getId(), address);
    orders.insertEvent(order.getId(), user.getId(), "ORDER_CREATED", "CART", "PENDING_PAYMENT",
      "Checkout created from cart");
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

  @Transactional
  public ShopOrderResponse pay(AppUser user, Long id, PaymentRequest request) {
    ShopOrderView order = orders.lock(user.getId(), id)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    PaymentMerchantAccount merchant = orders.merchantAccount(request.channel())
      .orElseThrow(() -> new BusinessRuleException("Payment channel is disabled"));
    String fingerprint = fingerprint(user.getId(), order, request);
    orders.ensurePaymentIdempotencyKey(request.idempotencyKey());
    orders.lockPaymentIdempotencyKey(request.idempotencyKey());
    Optional<PaymentView> existing = orders.paymentByKey(request.idempotencyKey());
    if (existing.isPresent()) {
      PaymentView payment = existing.get();
      if (!payment.getOrderId().equals(id)
        || !merchant.getId().equals(payment.getMerchantAccountId())
        || !fingerprint.equals(payment.getRequestFingerprint())) {
        throw new BusinessRuleException("Idempotency key was already used with different payment parameters");
      }
      redisIdempotency.rememberPayment(request.idempotencyKey(), payment.getPaymentNumber());
      return assemble(orders.find(user.getId(), id).orElseThrow(), existing.get());
    }
    if (!"PENDING_PAYMENT".equals(order.getStatus())) {
      throw new BusinessRuleException("Only pending orders can be paid");
    }
    if (!order.getPaymentDueAt().isAfter(Instant.now())) {
      throw new BusinessRuleException("Order payment deadline has expired");
    }
    String status = "PENDING";
    String reason = "";
    String paymentNumber = number("PAY");
    PaymentGatewayClient.Result remote = gateway.create(
      paymentNumber,
      request.channel(),
      "Order " + order.getOrderNumber(),
      order.getTotalAmount(),
      order.getCurrency(),
      "NONE");
    orders.insertPayment(id, merchant.getId(), number("PAY"), request.idempotencyKey(), request.channel(),
      order.getCurrency(), fingerprint, remote.channelTransactionNo(), order.getTotalAmount(), status, reason,
      null, remote.expiresInstant());
    PaymentView payment = orders.paymentByKey(request.idempotencyKey()).orElseThrow();
    orders.updateCheckout(payment.getId(), remote.checkoutUrl(), remote.actionType(), remote.qrPayload());
    orders.insertPaymentEvent(payment.getId(), UUID.randomUUID().toString(), "PAYMENT_PENDING", "CREATED",
      status, "Waiting for signed asynchronous notification");
    redisIdempotency.rememberPayment(request.idempotencyKey(), payment.getPaymentNumber());
    return assemble(
      orders.find(user.getId(), id).orElseThrow(),
      orders.paymentByKey(request.idempotencyKey()).orElseThrow());
  }

  private ShopOrderResponse assemble(ShopOrderView order, PaymentView payment) {
    List<ShopOrderResponse.Item> items = orders.items(order.getId())
      .stream()
      .map(ShopOrderResponse.Item::from)
      .toList();
    PaymentView resolved = payment != null ? payment : orders.latestPayment(order.getId()).orElse(null);
    RefundResponse refund = refunds.byOrder(order.getId())
      .map(value -> RefundResponse.from(value, refunds.events(value.getId())))
      .orElse(null);
    return new ShopOrderResponse(
      order.getId(),
      order.getOrderNumber(),
      order.getStatus(),
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

  private List<String> availableActions(ShopOrderView order, PaymentView payment, RefundResponse refund) {
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

  private String fingerprint(Long customerId, ShopOrderView order, PaymentRequest request) {
    String value = customerId + "|" + order.getId() + "|" + request.channel() + "|"
      + order.getTotalAmount().stripTrailingZeros().toPlainString() + "|" + order.getCurrency() + "|"
      + request.simulateFailure();
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }
}
