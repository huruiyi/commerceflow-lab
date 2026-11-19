package vip.fairy.commerceflow.commerce.ordering.application;

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
                              ShippingTemplateService shipping, ObjectMapper json) {
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
  }

  public CheckoutQuote quote(AppUser user, Long addressId, List<Long> itemIds) {
    Set<Long> selection = selection(itemIds);
    return transactions.execute(status -> price(user.getId(), addressId, selection).quote());
  }

  public ShopOrderResponse checkout(AppUser user, Long addressId, List<Long> itemIds, String quoteId) {
    Set<Long> selection = selection(itemIds);
    String scopeHash = fingerprint(new CheckoutScope(addressId, sorted(selection)));
    Checkout checkout = transactions.execute(status -> createOrder(user, addressId, selection, quoteId, scopeHash));
    Long orderId = checkout.orderId();
    inventoryDelivery.deliver(reservationCommandId(orderId));
    ShopOrderView order = orders.find(user.getId(), orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    if ("RESERVATION_REJECTED".equals(order.getInventoryStatus())) {
      List<CheckoutStockException.Line> problems;
      try {
        problems = checkout.items().isEmpty() ? List.of() : stockProblems(checkout.items());
      } catch (ExternalServiceUnavailableException exception) {
        problems = List.of();
      }
      throw new CheckoutStockException(problems);
    }
    return assemble(order, null, null);
  }

  private Checkout createOrder(AppUser user, Long addressId, Set<Long> selection, String quoteId, String scopeHash) {
    Long customerId = user.getId();
    if (carts.lockCart(customerId) == null) {
      throw new BusinessRuleException("Cart is empty");
    }
    var receipt = pricing.receipt(customerId, quoteId);
    if (receipt != null) {
      if (!scopeHash.equals(receipt.scopeHash())) {
        throw new BusinessRuleException("A checkout quote was already used for another request");
      }
      return new Checkout(receipt.orderId(), List.of());
    }
    PricedCart cart = price(customerId, addressId, selection);
    if (!cart.quote().quoteId().equals(quoteId)) {
      throw new CheckoutPriceChangedException(cart.quote());
    }
    inventory.requireWritable();
    var problems = stockProblems(cart.items());
    if (!problems.isEmpty()) {
      throw new CheckoutStockException(problems);
    }

    // A retry after Order V1 commits but before this transaction commits reuses its order number.
    String orderNumber = "ORD-" + quoteId.substring(0, 32).toUpperCase(Locale.ROOT);
    NewOrder order = new NewOrder(orderNumber, customerId, cart.quote().totalAmount(), "CNY",
      Instant.now().plus(lifecycle.getPaymentTimeout()), cart.quote().shippingFee(), cart.quote().discountAmount());
    orders.insertOrder(order, cart.orderItems(), OrderAddressSnapshot.from(cart.address()), customerId);
    String commandId = reservationCommandId(order.getId());
    var stockItems = cart.items().stream().map(item -> InventoryCommandPort.item(
      item.getProductId(), item.getProductName(), item.getQuantity())).toList();
    inventory.handle(InventoryCommandV1.reserveReviewedCheckout(commandId, order.getId(), stockItems));
    scheduledTasks.schedule(ScheduledTaskTypes.PAYMENT_TIMEOUT, "order:" + order.getId(), "ORDER",
      order.getId().toString(), order.getPaymentDueAt(), Map.of("orderId", order.getId()));
    if (carts.removeItems(customerId, cart.items().stream().map(CheckoutCartItem::getId).toList()) != cart.items().size()) {
      throw new IllegalStateException("Selected cart items changed during checkout");
    }
    pricing.saveReceipt(customerId, quoteId, scopeHash, order.getId());
    return new Checkout(order.getId(), cart.items());
  }

  private PricedCart price(Long customerId, Long addressId, Set<Long> selection) {
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
    BigDecimal products = orderItems.stream().map(OrderItemSnapshot::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal fee = shipping.fee(orderItems.getFirst().merchantId(), address.countryCode(), address.stateProvince());
    BigDecimal total = products.add(fee);
    if (total.compareTo(new BigDecimal("9999999999.99")) > 0) {
      throw new BusinessRuleException("Checkout amount exceeds the supported amount");
    }
    String quoteId = fingerprint(java.util.Arrays.asList(customerId, addressId, address,
      items.stream().map(CheckoutCartItem::getId).toList(), orderItems, fee.stripTrailingZeros()));
    return new PricedCart(items, orderItems, address, new CheckoutQuote(quoteId, products, BigDecimal.ZERO, fee, total));
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

  private record CheckoutScope(Long addressId, List<Long> itemIds) {
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

  private record Checkout(Long orderId, List<CheckoutCartItem> items) {
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
