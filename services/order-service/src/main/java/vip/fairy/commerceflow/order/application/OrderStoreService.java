package vip.fairy.commerceflow.order.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.order.application.RemoteOrderProtocolService.ProtocolConflictException;
import vip.fairy.commerceflow.order.application.contract.v1.OrderSearchV1;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.CreateOrder;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.ItemView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Mutation;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.MutationResult;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.MutationType;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.OrderView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Query;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.QueryResult;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderStoreMapper;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderLineRefundMapper;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderStoreMapper.GeneratedOrderKey;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderStoreMapper.OrderEvent;

@Service
public class OrderStoreService {

  private final OrderStoreMapper store;
  private final OrderLineRefundMapper lineRefunds;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  public OrderStoreService(OrderStoreMapper store, ObjectMapper mapper,
                           PlatformTransactionManager manager, OrderLineRefundMapper lineRefunds) {
    this.store = store;
    this.lineRefunds = lineRefunds;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
  }

  public OrderView create(CreateOrder request) {
    requireCreate(request);
    return transactions.execute(status -> {
      OrderView existing = store.findByNumber(request.orderNumber());
      if (existing != null) return existing;
      GeneratedOrderKey key = new GeneratedOrderKey();
      store.insertOrder(request, key);
      Long orderId = key.getId();
      for (var item : request.items()) {
        store.insertItem(orderId, item);
      }
      store.insertAddress(orderId, request.address());
      event(orderId, request.actorId(), "ORDER_CREATED", "CART", "PENDING_PAYMENT",
        "Checkout created from cart", "USER", null);
      return store.findById(orderId);
    });
  }

  public QueryResult query(Query request) {
    return switch (request.type()) {
      case STATUS -> {
        String status = store.status(request.orderId());
        yield new QueryResult(status != null, status, null, null, null, null, null, null, null);
      }
      case LIST_CUSTOMER -> resultOrders(store.listCustomer(request.customerId()));
      case FIND_CUSTOMER -> resultOrder(store.findCustomer(request));
      case FIND_BY_ID -> resultOrder(store.findById(request.orderId()));
      case LIST_MERCHANT -> resultOrders(store.listMerchant(request.merchantId()));
      case FIND_MERCHANT -> resultOrder(store.findMerchant(request));
      case ITEMS -> new QueryResult(true, null, null, null, null,
        store.items(request.orderId()), null, null, null);
      case SHIPMENT_LINES -> shipmentLines(request);
      case ADDRESS -> {
        var address = store.address(request.orderId());
        yield new QueryResult(address != null, null, null, null, null, null, address, null, null);
      }
      case EVENTS -> new QueryResult(true, null, null, null, null, null, null,
        store.events(request.orderId()), null);
      case ORDER_CUSTOMER -> {
        Long customerId = store.customerId(request.orderId());
        yield new QueryResult(customerId != null, null, customerId, null, null, null, null, null, null);
      }
      case REVIEWABLE -> {
        var item = store.reviewable(request);
        yield new QueryResult(item != null, null, null, null, null, null, null, null, item);
      }
    };
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public OrderSearchV1.Result search(OrderSearchV1.Request request) {
    var criteria = request.criteria();
    if ((request.customerId() == null) == (request.merchantId() == null)
        || criteria == null || criteria.page() < 0 || criteria.size() < 1 || criteria.size() > 20
        || criteria.keyword() == null || criteria.keyword().length() > 120
        || criteria.productIds() == null) {
      throw new IllegalArgumentException("Invalid order list filters or scope");
    }
    long total = store.countSearch(request);
    int page = total == 0 ? 0 : (int) Math.min(criteria.page(), (total - 1) / criteria.size());
    var rows = total == 0 ? List.<OrderView>of() : store.search(request, (long) page * criteria.size());
    var pending = request.merchantId() == null
      ? new OrderSearchV1.PendingCounts(0, 0) : store.countPendingMerchant(request.merchantId());
    return new OrderSearchV1.Result(rows, total, page, criteria.size(),
      pending.pendingConfirm(), pending.pendingShip());
  }

  private QueryResult shipmentLines(Query request) {
    var order = store.findMerchant(request);
    var items = order == null ? List.<ItemView>of() : lineRefunds.shipmentItems(request.orderId());
    boolean canShip = !items.isEmpty()
      && items.stream().allMatch(item -> request.merchantId().equals(item.merchantId()));
    return new QueryResult(canShip, null, null, canShip ? order : null, null,
      canShip ? items : List.of(), null, null, null);
  }

  public MutationResult mutate(Mutation mutation) {
    requireMutation(mutation);
    String payload = mapper.writeValueAsString(mutation);
    String fingerprint = sha256(payload);
    return transactions.execute(status -> {
      var existing = store.lockMutation(mutation.mutationId());
      if (existing != null) {
        if (!fingerprint.equals(existing.payloadFingerprint())) {
          throw new ProtocolConflictException("Mutation id was reused with a different payload");
        }
        return mapper.readValue(existing.responsePayload(), MutationResult.class);
      }
      store.insertMutation(mutation.mutationId(), fingerprint);
      MutationResult result = mutation.type() == MutationType.START_REFUND
        ? startRefund(mutation) : new MutationResult(apply(mutation));
      store.finishMutation(mutation.mutationId(), mapper.writeValueAsString(result));
      return result;
    });
  }

  private int apply(Mutation value) {
    return switch (value.type()) {
      case INSERT_EVENT -> event(value.orderId(), value.actorId(), value.eventType(),
        value.fromStatus(), value.toStatus(), value.detail(), value.triggerSource(), value.scheduledTaskId());
      case MARK_LOGISTICS_DELIVERED -> store.markLogisticsDelivered(value);
      case UPDATE_STATUS -> store.updateStatus(value);
      case UPDATE_INVENTORY_STATUS -> store.updateInventoryStatus(value);
      case START_INVENTORY_COMMIT -> store.startInventoryCommit(value);
      case START_CANCELLATION -> store.startCancellation(value);
      case FINISH_CANCELLATION -> store.finishCancellation(value);
      case RESTORE_PAID_CANCELLATION_RACE -> store.restorePaidCancellationRace(value);
      case RESTORE_PAID_LATE_SUCCESS -> store.restorePaidLateSuccess(value);
      case MARK_RECEIVED -> store.markReceived(value);
      case MARK_COMPLETED -> store.markCompleted(value);
      case MERCHANT_CONFIRM -> confirmByMerchant(value);
      case RESERVE_SHIPMENT -> store.reserveShipment(value, shipmentKey(value), false);
      case RESERVE_NEW_SHIPMENT -> store.reserveShipment(value, shipmentKey(value), true);
      case MERCHANT_SHIP -> shipByMerchant(value);
      case UPDATE_SHIPMENT -> updateShipment(value);
      case START_REFUND -> throw new IllegalStateException("Refund mutation requires a previous status result");
    };
  }

  private int updateShipment(Mutation value) {
    if (value.expectedShipmentVersion() == null || value.expectedShipmentVersion() < 0
        || value.actorId() == null || value.detail() == null) {
      throw new IllegalArgumentException("Shipment update requires a merchant, expected version and event details");
    }
    int changed = store.updateShipment(value);
    if (changed == 1) {
      event(value.orderId(), value.actorId(), "SHIPMENT_UPDATED", "SHIPPED", "SHIPPED", value.detail(), "USER", null);
    }
    return changed;
  }

  private int confirmByMerchant(Mutation value) {
    int changed = store.confirmByMerchant(value);
    if (changed == 1) {
      event(value.orderId(), value.actorId(), "MERCHANT_CONFIRMED", "PAID", "CONFIRMED",
        "Merchant accepted the order", "USER", null);
    }
    return changed;
  }

  private int shipByMerchant(Mutation value) {
    int changed = store.shipByMerchant(value, shipmentKey(value));
    if (changed == 1) {
      event(value.orderId(), value.actorId(), "ORDER_SHIPPED", "CONFIRMED", "SHIPPED",
        value.carrier() + " / " + value.trackingNumber(), "USER", null);
    }
    return changed;
  }

  private String shipmentKey(Mutation value) {
    if (value.actorId() == null || value.carrier() == null || value.trackingNumber() == null) {
      throw new IllegalArgumentException("Merchant, carrier and tracking number are required");
    }
    return sha256(mapper.writeValueAsString(List.of(value.actorId(), value.carrier(), value.trackingNumber())));
  }

  private MutationResult startRefund(Mutation value) {
    String previous = store.lockOrderStatus(value.orderId());
    if (!"PAID".equals(previous) && !"CONFIRMED".equals(previous)) return new MutationResult(0);
    if (store.startRefund(value.orderId(), previous) != 1) return new MutationResult(0);
    event(value.orderId(), store.customerId(value.orderId()), "ORDER_REFUND_REQUESTED", previous, "REFUNDING",
      value.detail(), "PAYMENT_ORCHESTRATION", null);
    return new MutationResult(1, previous);
  }

  private int event(Long orderId, Long actorId, String type, String from, String to,
                    String detail, String trigger, Long scheduledTaskId) {
    return store.insertEvent(new OrderEvent(orderId, actorId, type, from, to, detail, trigger, scheduledTaskId));
  }

  private QueryResult resultOrder(OrderView order) {
    return new QueryResult(order != null, null, null, order, null, null, null, null, null);
  }

  private QueryResult resultOrders(List<OrderView> rows) {
    return new QueryResult(true, null, null, null, rows, null, null, null, null);
  }

  private void requireCreate(CreateOrder value) {
    if (value == null || value.orderNumber() == null || value.orderNumber().isBlank()
        || value.customerId() == null || value.totalAmount() == null || value.currency() == null
        || value.paymentDueAt() == null || value.items() == null || value.items().isEmpty()
        || value.address() == null || !validMoney(value.totalAmount())
        || !validMoney(value.shippingFee()) || !validMoney(value.discountAmount())) {
      throw new IllegalArgumentException("Order create request is incomplete");
    }
    if (value.couponId() != null
        && (!value.couponId().matches("[0-9a-f-]{36}") || value.discountAmount().signum() <= 0)) {
      throw new IllegalArgumentException("Coupon snapshot requires an instance and a positive reduction");
    }
    BigDecimal products = BigDecimal.ZERO;
    BigDecimal discounts = BigDecimal.ZERO;
    for (var item : value.items()) {
      if (item == null || item.quantity() <= 0 || !validMoney(item.unitPrice()) || !validMoney(item.subtotal())
          || !validMoney(item.discountAmount()) || item.discountAmount().compareTo(item.subtotal()) > 0
          || item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())).compareTo(item.subtotal()) != 0) {
        throw new IllegalArgumentException("Order line amount is invalid");
      }
      products = products.add(item.subtotal());
      discounts = discounts.add(item.discountAmount());
    }
    if (!validMoney(products) || discounts.compareTo(value.discountAmount()) != 0
        || products.subtract(discounts).add(value.shippingFee()).compareTo(value.totalAmount()) != 0) {
      throw new IllegalArgumentException("Order amount does not match its lines and shipping");
    }
  }

  private boolean validMoney(BigDecimal amount) {
    return amount != null && amount.signum() >= 0 && amount.stripTrailingZeros().scale() <= 2
      && amount.compareTo(new BigDecimal("9999999999.99")) <= 0;
  }

  private void requireMutation(Mutation value) {
    if (value == null || value.mutationId() == null || value.mutationId().isBlank()
        || value.type() == null || value.orderId() == null) {
      throw new IllegalArgumentException("Order mutation request is incomplete");
    }
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
