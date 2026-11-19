package vip.fairy.commerceflow.commerce.ordering.application;

import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.StoreTradeV1;

import vip.fairy.commerceflow.commerce.cart.domain.CheckoutAddressSnapshot;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.cart.application.port.CheckoutCartPort;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryReservationQuery;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.StockAvailabilityQuery;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsService;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.ordering.api.CreatePaymentRequest;
import vip.fairy.commerceflow.commerce.ordering.domain.NewOrder;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
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
import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.CheckoutIntentMapper;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.CheckoutIntentMapper.Intent;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.CheckoutPricingMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PurchaseOrderService {

  private static final Logger LOG = LoggerFactory.getLogger(PurchaseOrderService.class);

  private final OrderStorePort orders;
  private final CheckoutCartPort carts;
  private final PaymentOrchestrationV1 payments;
  private final PaymentWriteAdmissionV1 paymentWrites;
  private final OrderLifecycleProperties lifecycle;
  private final ScheduledTaskService scheduledTasks;
  private final LogisticsService logistics;
  private final InventoryCommandPort inventory;
  private final InventoryReservationQuery reservations;
  private final StockAvailabilityQuery stocks;
  private final InventoryCommandPort.ImmediateDelivery inventoryDelivery;
  private final TransactionTemplate transactions;
  private final CheckoutPricingMapper pricing;
  private final ShippingTemplateService shipping;
  private final ObjectMapper json;
  private final CheckoutIntentMapper intents;
  private final CouponService coupons;
  private final CheckoutCouponLifecycle couponLifecycle;
  private final StoreTradeV1 stores;

  public PurchaseOrderService(OrderStorePort orders, CheckoutCartPort carts,
                              PaymentOrchestrationV1 payments,
                              PaymentWriteAdmissionV1 paymentWrites,
                              OrderLifecycleProperties lifecycle, ScheduledTaskService scheduledTasks,
                              LogisticsService logistics,
                              InventoryCommandPort inventory,
                              InventoryReservationQuery reservations,
                              StockAvailabilityQuery stocks,
                              InventoryCommandPort.ImmediateDelivery inventoryDelivery,
                              TransactionTemplate transactions, CheckoutPricingMapper pricing,
                              ShippingTemplateService shipping, ObjectMapper json, CheckoutIntentMapper intents,
                              CouponService coupons, CheckoutCouponLifecycle couponLifecycle, StoreTradeV1 stores) {
    this.orders = orders;
    this.carts = carts;
    this.payments = payments;
    this.paymentWrites = paymentWrites;
    this.lifecycle = lifecycle;
    this.scheduledTasks = scheduledTasks;
    this.logistics = logistics;
    this.inventory = inventory;
    this.reservations = reservations;
    this.stocks = stocks;
    this.inventoryDelivery = inventoryDelivery;
    this.transactions = transactions;
    this.pricing = pricing;
    this.shipping = shipping;
    this.json = json;
    this.intents = intents;
    this.coupons = coupons;
    this.couponLifecycle = couponLifecycle;
    this.stores = stores;
  }

  public CheckoutQuote quote(AppUser user, Long addressId, List<Long> itemIds, UUID couponId) {
    Set<Long> selection = selection(itemIds);
    return transactions.execute(status -> price(user.getId(), addressId, selection, couponId, false).quote());
  }

  public ShopOrderResponse checkout(AppUser user, Long addressId, List<Long> itemIds, UUID couponId, String quoteId) {
    Set<Long> selection = selection(itemIds);
    String scopeHash = fingerprint(new CheckoutScope(addressId, sorted(selection), couponId));
    Long replay = transactions.execute(status ->
      prepare(user.getId(), addressId, selection, couponId, quoteId, scopeHash));
    Long orderId = replay == null ? finish(quoteId) : replay;
    inventoryDelivery.deliver(reservationCommandId(orderId));
    ShopOrderView order = orders.find(user.getId(), orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    if ("RESERVATION_REJECTED".equals(order.getInventoryStatus())) {
      List<CheckoutStockException.Line> problems;
      try {
        var intent = intents.find(quoteId);
        problems = intent == null ? List.of() : stockProblems(payload(intent).cartItems());
      } catch (ExternalServiceUnavailableException exception) {
        problems = List.of();
      }
      throw new CheckoutStockException(problems);
    }
    return assemble(order, null, null);
  }

  private Long prepare(Long customerId, Long addressId, Set<Long> selection, UUID couponId,
                       String quoteId, String scopeHash) {
    if (carts.lockCart(customerId) == null) throw new BusinessRuleException("Cart is empty");
    var receipt = pricing.receipt(customerId, quoteId);
    if (receipt != null) {
      requireSameRequest(scopeHash, receipt.scopeHash());
      return receipt.orderId();
    }
    var existing = intents.find(quoteId);
    if (existing != null) {
      if (!customerId.equals(existing.customerId())) throw new BusinessRuleException("Checkout owner mismatch");
      requireSameRequest(scopeHash, existing.scopeHash());
      return existing.orderId();
    }
    if (intents.pending(customerId) != null) {
      throw new BusinessRuleException("A previous checkout is still being completed");
    }
    PricedCart priced = price(customerId, addressId, selection, couponId, true);
    if (!priced.quote().quoteId().equals(quoteId)) throw new CheckoutPriceChangedException(priced.quote());
    inventory.requireWritable();
    var problems = stockProblems(priced.items());
    if (!problems.isEmpty()) throw new CheckoutStockException(problems);

    String orderNumber = "ORD-" + quoteId.substring(0, 32).toUpperCase(Locale.ROOT);
    String id = priced.quote().couponId();
    coupons.hold(id, customerId, quoteId);
    var frozen = new FrozenCheckout(priced.items(), priced.orderItems(), OrderAddressSnapshot.from(priced.address()),
      priced.quote().totalAmount(), priced.quote().shippingFee(), priced.quote().discountAmount(),
      Instant.now().plus(lifecycle.getPaymentTimeout()));
    intents.insert(new Intent(quoteId, customerId, scopeHash, orderNumber, null, id, "PREPARED",
      json.writeValueAsString(frozen)));
    return null;
  }

  private Long finish(String quoteId) {
    return transactions.execute(status -> {
      Intent intent = intents.lock(quoteId);
      if (intent == null) throw new IllegalStateException("Checkout intent is missing");
      if (intent.orderId() != null) return intent.orderId();
      var snapshot = payload(intent);
      inventory.requireWritable();
      var order = new NewOrder(intent.orderNumber(), intent.customerId(), snapshot.total(), "CNY",
        snapshot.paymentDueAt(), snapshot.fee(), snapshot.discount(), intent.couponId());
      orders.insertOrder(order, snapshot.orderItems(), snapshot.address(), intent.customerId());
      String commandId = reservationCommandId(order.getId());
      var stockItems = snapshot.cartItems().stream().map(item -> InventoryCommandPort.item(
        item.getProductId(), item.getProductName(), item.getQuantity())).toList();
      inventory.handle(InventoryCommandV1.reserveReviewedCheckout(commandId, order.getId(), stockItems));
      scheduledTasks.schedule(ScheduledTaskTypes.PAYMENT_TIMEOUT, "order:" + order.getId(), "ORDER",
        order.getId().toString(), order.getPaymentDueAt(), Map.of("orderId", order.getId()));

      // Preserve cart edits made while a confirmed checkout was being recovered.
      carts.lockCart(intent.customerId());
      var frozen = snapshot.cartItems().stream().collect(Collectors.toMap(CheckoutCartItem::getId, item -> item));
      var unchanged = carts.lockItems(intent.customerId()).stream().filter(item -> {
        CheckoutCartItem prior = frozen.get(item.getId());
        return prior != null && prior.getProductId().equals(item.getProductId())
          && prior.getQuantity() == item.getQuantity();
      }).map(CheckoutCartItem::getId).toList();
      if (!unchanged.isEmpty() && carts.removeItems(intent.customerId(), unchanged) != unchanged.size()) {
        throw new IllegalStateException("Confirmed cart lines changed while locked");
      }
      pricing.saveReceipt(intent.customerId(), quoteId, intent.scopeHash(), order.getId());
      intents.bind(quoteId, order.getId(), intent.couponId() == null ? "COMPLETE" : "BOUND");
      return order.getId();
    });
  }

  @Scheduled(fixedDelayString = "${commerceflow.checkout.recovery-delay:2s}")
  public void recoverCheckouts() {
    for (String quoteId : intents.due()) {
      try {
        Intent intent = intents.find(quoteId);
        if ("PREPARED".equals(intent.status())) {
          inventoryDelivery.deliver(reservationCommandId(finish(quoteId)));
        } else {
          reconcileCoupon(intent);
        }
      } catch (RuntimeException failure) {
        LOG.warn("Checkout {} will retry: {}", quoteId, failure.getMessage());
      } finally {
        intents.defer(quoteId);
      }
    }
  }

  private void reconcileCoupon(Intent candidate) {
    transactions.executeWithoutResult(status -> {
      Intent intent = intents.lock(candidate.quoteId());
      if (!"BOUND".equals(intent.status())) return;
      var order = orders.find(intent.customerId(), intent.orderId())
        .orElseThrow(() -> new IllegalStateException("Confirmed checkout order is missing"));
      if (Set.of("PAID", "CONFIRMED", "SHIPPED", "RECEIVED", "COMPLETED", "REFUNDING", "REFUNDED")
          .contains(order.getStatus())) {
        couponLifecycle.resolve(intent, true);
      } else if ("CANCELLED".equals(order.getStatus()) || "RESERVATION_REJECTED".equals(order.getInventoryStatus())) {
        couponLifecycle.resolve(intent, false);
      }
    });
  }

  private void requireSameRequest(String scopeHash, String previous) {
    if (!scopeHash.equals(previous)) throw new BusinessRuleException("Checkout quote was used for another request");
  }

  private FrozenCheckout payload(Intent intent) {
    return json.readValue(intent.payload(), FrozenCheckout.class);
  }

  private record FrozenCheckout(List<CheckoutCartItem> cartItems, List<OrderItemSnapshot> orderItems,
                                OrderAddressSnapshot address, BigDecimal total, BigDecimal fee,
                                BigDecimal discount, Instant paymentDueAt) {
  }

  private PricedCart price(Long customerId, Long addressId, Set<Long> selection, UUID couponId, boolean lockCampaign) {
    List<CheckoutCartItem> items = selectedCartItems(customerId, selection);
    if (items.isEmpty()) {
      throw new BusinessRuleException("Cart is empty");
    }
    CheckoutAddressSnapshot address = addressId == null
      ? carts.selectedAddress(customerId) : carts.address(customerId, addressId);
    if (address == null) {
      throw new BusinessRuleException("Select a valid shipping address before checkout");
    }
    List<OrderItemSnapshot> orderItems = currentOrderItems(items);
    stores.requireActive(orderItems.getFirst().merchantId(), lockCampaign);
    BigDecimal products = orderItems.stream().map(OrderItemSnapshot::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal fee = shipping.fee(orderItems.getFirst().merchantId(), address.countryCode(), address.stateProvince());
    String id = couponId == null ? null : couponId.toString();
    var coupon = coupons.price(id, customerId, orderItems.getFirst().merchantId(), products, lockCampaign);
    BigDecimal discount = coupon == null ? BigDecimal.ZERO : coupon.discountAmount();
    if (coupon != null) orderItems = CouponAllocation.allocate(orderItems, products, discount);
    BigDecimal total = products.subtract(discount).add(fee);
    if (total.compareTo(new BigDecimal("9999999999.99")) > 0) {
      throw new BusinessRuleException("Checkout amount exceeds the supported amount");
    }
    var quoteFacts = new java.util.ArrayList<>(java.util.Arrays.asList(customerId, addressId, address,
      items.stream().map(CheckoutCartItem::getId).toList(), orderItems, fee.stripTrailingZeros()));
    if (coupon != null) quoteFacts.addAll(List.of(id, coupon.campaignId()));
    String quoteId = fingerprint(quoteFacts);
    return new PricedCart(items, orderItems, address, new CheckoutQuote(quoteId, products, discount, fee, total, id));
  }

  private Set<Long> selection(List<Long> itemIds) {
    if (itemIds == null) {
      return null;
    }
    Set<Long> selection = Set.copyOf(itemIds);
    if (selection.isEmpty() || selection.size() != itemIds.size()) {
      throw new IllegalArgumentException("Select distinct cart items before checkout");
    }
    return selection;
  }

  private List<Long> sorted(Set<Long> selection) {
    return selection == null ? List.of() : selection.stream().sorted().toList();
  }

  private String fingerprint(Object input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record CheckoutScope(Long addressId, List<Long> itemIds,
                               @com.fasterxml.jackson.annotation.JsonInclude(
                                 com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID couponId) {
  }

  private record PricedCart(List<CheckoutCartItem> items, List<OrderItemSnapshot> orderItems,
                            CheckoutAddressSnapshot address, CheckoutQuote quote) {
  }

  private List<OrderItemSnapshot> currentOrderItems(List<CheckoutCartItem> items) {
    List<CheckoutCartItem> priced;
    try {
      priced = carts.priceItems(items);
    } catch (ResourceNotFoundException exception) {
      throw new CheckoutStockException(stockProblems(items));
    }
    if (priced.stream().anyMatch(item -> item.getMerchantId() == null)) {
      throw new BusinessRuleException("A cart product has no merchant");
    }
    if (priced.stream().map(CheckoutCartItem::getMerchantId).distinct().count() > 1) {
      throw new BusinessRuleException("Check out products from one merchant at a time");
    }
    return priced.stream().map(OrderItemSnapshot::from).toList();
  }

  private List<CheckoutCartItem> selectedCartItems(Long customerId, Set<Long> selection) {
    var locked = carts.lockItems(customerId);
    if (selection == null) return locked;
    var selected = locked.stream().filter(item -> selection.contains(item.getId())).toList();
    if (selected.size() != selection.size()) {
      throw new BusinessRuleException("Selected cart items changed. Review the cart before checking out.");
    }
    return selected;
  }

  private List<CheckoutStockException.Line> stockProblems(List<CheckoutCartItem> items) {
    var available = stocks.availability(items.stream().map(CheckoutCartItem::getProductId).toList());
    return items.stream().filter(item -> {
      var stock = available.get(item.getProductId());
      return stock == null || !stock.listed() || stock.available() < item.getQuantity();
    }).map(item -> {
      var stock = available.get(item.getProductId());
      return new CheckoutStockException.Line(item.getProductId(), item.getProductName(), item.getQuantity(),
        stock == null ? null : stock.available(), stock != null && stock.listed());
    }).toList();
  }

  private String reservationCommandId(Long orderId) {
    return "order:" + orderId + ":reserve";
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

  public ShopOrderResponse response(ShopOrderView order) {
    return assemble(order, null);
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
    if (!"RESERVED".equals(order.getInventoryStatus())) {
      throw new BusinessRuleException("Order inventory has not been reserved");
    }
    Map<Long, Integer> required = orders.items(id).stream().collect(Collectors.toMap(
      OrderItemView::getProductId, OrderItemView::getQuantity, Math::addExact));
    if (required.isEmpty() || !required.equals(reservations.activeReservations(id))) {
      throw new BusinessRuleException("Inventory reservations do not cover the order items");
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
    PaymentQueryV1 query = new PaymentQueryV1(PaymentQueryV1.VERSION, order.getId());
    PaymentSnapshotV1 resolved = payment != null ? payment : payments.latest(query).orElse(null);
    RefundSnapshotV1 refund = payments.refund(query).orElse(null);
    return assemble(order, resolved, refund);
  }

  private ShopOrderResponse assemble(ShopOrderView order, PaymentSnapshotV1 resolved,
                                     RefundSnapshotV1 refund) {
    List<ShopOrderResponse.Item> items = orders.items(order.getId())
      .stream()
      .map(ShopOrderResponse.Item::from)
      .toList();
    return new ShopOrderResponse(
      order.getId(),
      order.getOrderNumber(),
      order.getStatus(),
      order.getInventoryStatus(),
      order.getTotalAmount(),
      order.getProductSubtotal(),
      order.getShippingFee(),
      order.getDiscountAmount(),
      order.getCouponId(),
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
        if ("RESERVED".equals(order.getInventoryStatus())) {
          actions.add("PAY");
        }
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
        actions.add("REPURCHASE");
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

  private String traceparent() {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }
}
