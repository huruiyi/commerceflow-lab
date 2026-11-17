package vip.fairy.commerceflow.order.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.order.application.RemoteOrderProtocolService.ProtocolConflictException;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.AddressView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.CreateOrder;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.EventView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.ItemView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Mutation;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.MutationResult;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.OrderView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Query;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.QueryResult;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.ReviewableItem;

@Service
public class OrderStoreService {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  public OrderStoreService(JdbcTemplate jdbc, ObjectMapper mapper,
                           PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
  }

  public OrderView create(CreateOrder request) {
    requireCreate(request);
    return transactions.execute(status -> {
      List<OrderView> existing = jdbc.query("SELECT * FROM shop_orders WHERE order_number=?",
        this::order, request.orderNumber());
      if (!existing.isEmpty()) return existing.getFirst();
      GeneratedKeyHolder key = new GeneratedKeyHolder();
      jdbc.update(connection -> {
        var statement = connection.prepareStatement(
          "INSERT INTO shop_orders(order_number,customer_id,status,inventory_status,total_amount,"
            + "currency,payment_due_at,created_at,updated_at) VALUES (?,?,'PENDING_PAYMENT',"
            + "'WAITING_RESERVATION',?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
          java.sql.Statement.RETURN_GENERATED_KEYS);
        statement.setString(1, request.orderNumber());
        statement.setLong(2, request.customerId());
        statement.setBigDecimal(3, request.totalAmount());
        statement.setString(4, request.currency());
        statement.setTimestamp(5, Timestamp.from(request.paymentDueAt()));
        return statement;
      }, key);
      long orderId = key.getKey().longValue();
      for (var item : request.items()) {
        jdbc.update("INSERT INTO order_items(order_id,product_id,merchant_id,product_name,"
            + "thumbnail_url,unit_price,quantity,subtotal) VALUES (?,?,?,?,?,?,?,?)",
          orderId, item.productId(), item.merchantId(), item.productName(), item.thumbnailUrl(),
          item.unitPrice(), item.quantity(), item.subtotal());
      }
      var address = request.address();
      jdbc.update("INSERT INTO order_shipping_addresses(order_id,recipient_name,phone,country_code,"
          + "state_province,city,district,address_line1,address_line2,postal_code,label) "
          + "VALUES (?,?,?,?,?,?,?,?,?,?,?)", orderId, address.recipientName(), address.phone(),
        address.countryCode(), address.stateProvince(), address.city(), address.district(),
        address.addressLine1(), address.addressLine2(), address.postalCode(), address.label());
      event(orderId, request.actorId(), "ORDER_CREATED", "CART", "PENDING_PAYMENT",
        "Checkout created from cart", "USER", null);
      return byId(orderId).getFirst();
    });
  }

  public QueryResult query(Query request) {
    return switch (request.type()) {
      case STATUS -> scalarStatus(request.orderId());
      case LIST_CUSTOMER -> resultOrders(jdbc.query(
        "SELECT * FROM shop_orders WHERE customer_id=? ORDER BY created_at DESC,id DESC",
        this::order, request.customerId()));
      case FIND_CUSTOMER -> resultOrder(jdbc.query(
        "SELECT * FROM shop_orders WHERE id=? AND customer_id=?", this::order,
        request.orderId(), request.customerId()));
      case FIND_BY_ID -> resultOrder(byId(request.orderId()));
      case LIST_MERCHANT -> resultOrders(jdbc.query(
        "SELECT DISTINCT o.* FROM shop_orders o JOIN order_items i ON i.order_id=o.id "
          + "WHERE i.merchant_id=? ORDER BY o.created_at DESC", this::order,
        request.merchantId()));
      case FIND_MERCHANT -> resultOrder(jdbc.query(
        "SELECT o.* FROM shop_orders o WHERE o.id=? AND EXISTS (SELECT 1 FROM order_items i "
          + "WHERE i.order_id=o.id AND i.merchant_id=?)", this::order,
        request.orderId(), request.merchantId()));
      case ITEMS -> new QueryResult(true, null, null, null, null,
        jdbc.query("SELECT * FROM order_items WHERE order_id=? ORDER BY id", this::item,
          request.orderId()), null, null, null);
      case ADDRESS -> resultAddress(request.orderId());
      case EVENTS -> new QueryResult(true, null, null, null, null, null, null,
        jdbc.query("SELECT id,actor_id,event_type,from_status,to_status,detail,trigger_source,"
            + "scheduled_task_id,created_at FROM order_events WHERE order_id=? ORDER BY created_at,id",
          this::eventView, request.orderId()), null);
      case ORDER_CUSTOMER -> resultCustomer(request.orderId());
      case REVIEWABLE -> resultReviewable(request.customerId(), request.itemId());
    };
  }

  public MutationResult mutate(Mutation mutation) {
    requireMutation(mutation);
    String payload = mapper.writeValueAsString(mutation);
    String fingerprint = sha256(payload);
    return transactions.execute(status -> {
      List<Map<String,Object>> existing = jdbc.queryForList(
        "SELECT payload_fingerprint,response_payload FROM order_remote_message_inbox "
          + "WHERE message_id=? FOR UPDATE", mutation.mutationId());
      if (!existing.isEmpty()) {
        if (!fingerprint.equals(existing.getFirst().get("payload_fingerprint"))) {
          throw new ProtocolConflictException("Mutation id was reused with a different payload");
        }
        return mapper.readValue(String.valueOf(existing.getFirst().get("response_payload")),
          MutationResult.class);
      }
      jdbc.update("INSERT INTO order_remote_message_inbox(message_id,message_kind,payload_fingerprint,"
          + "processing_status) VALUES (?,'MUTATION',?,'RECEIVED')",
        mutation.mutationId(), fingerprint);
      int changed = apply(mutation);
      MutationResult result = new MutationResult(changed);
      jdbc.update("UPDATE order_remote_message_inbox SET processing_status='APPLIED',response_payload=?,"
          + "processed_at=CURRENT_TIMESTAMP(6) WHERE message_id=?",
        mapper.writeValueAsString(result), mutation.mutationId());
      return result;
    });
  }

  private int apply(Mutation value) {
    return switch (value.type()) {
      case MARK_LOGISTICS_DELIVERED -> jdbc.update(
        "UPDATE shop_orders SET logistics_delivered_at=COALESCE(logistics_delivered_at,?),"
          + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status IN ('SHIPPED','RECEIVED','COMPLETED')",
        value.occurredAt(), value.orderId());
      case INSERT_EVENT -> event(value.orderId(), value.actorId(), value.eventType(),
        value.fromStatus(), value.toStatus(), value.detail(), value.triggerSource(),
        value.scheduledTaskId());
      case UPDATE_STATUS -> jdbc.update("UPDATE shop_orders SET status=?,updated_at=CURRENT_TIMESTAMP(6) "
        + "WHERE id=? AND status=?", value.toStatus(), value.orderId(), value.fromStatus());
      case UPDATE_INVENTORY_STATUS -> jdbc.update("UPDATE shop_orders SET inventory_status=?,"
        + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND inventory_status=?",
        value.toStatus(), value.orderId(), value.fromStatus());
      case START_CANCELLATION -> jdbc.update("UPDATE shop_orders SET status='CANCELLING',cancel_reason=?,"
        + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='PENDING_PAYMENT'",
        value.reason(), value.orderId());
      case FINISH_CANCELLATION -> jdbc.update("UPDATE shop_orders SET status='CANCELLED',"
        + "cancelled_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) "
        + "WHERE id=? AND status='CANCELLING'", value.orderId());
      case RESTORE_PAID_CANCELLATION_RACE -> jdbc.update("UPDATE shop_orders SET status='PAID',"
        + "cancel_reason=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='CANCELLING'",
        value.orderId());
      case RESTORE_PAID_LATE_SUCCESS -> jdbc.update("UPDATE shop_orders SET status='PAID',"
        + "cancelled_at=NULL,cancel_reason='Recovered after late successful payment',"
        + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='CANCELLED'", value.orderId());
      case MARK_RECEIVED -> jdbc.update("UPDATE shop_orders SET status='RECEIVED',received_at=?,"
        + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='SHIPPED'",
        value.occurredAt(), value.orderId());
      case MARK_COMPLETED -> jdbc.update("UPDATE shop_orders SET status='COMPLETED',completed_at=?,"
        + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RECEIVED'",
        value.occurredAt(), value.orderId());
      case MERCHANT_CONFIRM -> jdbc.update("UPDATE shop_orders SET status='CONFIRMED',"
        + "confirmed_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) "
        + "WHERE id=? AND status='PAID'", value.orderId());
      case MERCHANT_SHIP -> jdbc.update("UPDATE shop_orders SET status='SHIPPED',carrier=?,"
        + "tracking_number=?,shipped_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) "
        + "WHERE id=? AND status='CONFIRMED'", value.carrier(), value.trackingNumber(), value.orderId());
      case UPDATE_SHIPMENT -> jdbc.update("UPDATE shop_orders SET carrier=?,tracking_number=?,"
        + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='SHIPPED'",
        value.carrier(), value.trackingNumber(), value.orderId());
    };
  }

  private int event(Long orderId, Long actorId, String type, String from, String to,
                    String detail, String trigger, Long scheduledTaskId) {
    return jdbc.update("INSERT INTO order_events(order_id,actor_id,event_type,from_status,to_status,"
        + "detail,trigger_source,scheduled_task_id,created_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",
      orderId, actorId, type, from, to, detail, trigger, scheduledTaskId);
  }

  private QueryResult scalarStatus(Long orderId) {
    List<String> rows = jdbc.query("SELECT status FROM shop_orders WHERE id=?",
      (rs, row) -> rs.getString(1), orderId);
    return new QueryResult(!rows.isEmpty(), rows.isEmpty() ? null : rows.getFirst(), null,
      null, null, null, null, null, null);
  }

  private QueryResult resultOrder(List<OrderView> rows) {
    return new QueryResult(!rows.isEmpty(), null, null, rows.isEmpty() ? null : rows.getFirst(),
      null, null, null, null, null);
  }

  private QueryResult resultOrders(List<OrderView> rows) {
    return new QueryResult(true, null, null, null, rows, null, null, null, null);
  }

  private QueryResult resultAddress(Long orderId) {
    List<AddressView> rows = jdbc.query("SELECT * FROM order_shipping_addresses WHERE order_id=?",
      this::address, orderId);
    return new QueryResult(!rows.isEmpty(), null, null, null, null, null,
      rows.isEmpty() ? null : rows.getFirst(), null, null);
  }

  private QueryResult resultCustomer(Long orderId) {
    List<Long> rows = jdbc.query("SELECT customer_id FROM shop_orders WHERE id=?",
      (rs, row) -> rs.getLong(1), orderId);
    return new QueryResult(!rows.isEmpty(), null, rows.isEmpty() ? null : rows.getFirst(),
      null, null, null, null, null, null);
  }

  private QueryResult resultReviewable(Long customerId, Long itemId) {
    List<ReviewableItem> rows = jdbc.query("SELECT i.id,i.product_id FROM order_items i "
        + "JOIN shop_orders o ON o.id=i.order_id WHERE i.id=? AND o.customer_id=? "
        + "AND o.status IN ('RECEIVED','COMPLETED')",
      (rs, row) -> new ReviewableItem(rs.getLong(1), rs.getLong(2)), itemId, customerId);
    return new QueryResult(!rows.isEmpty(), null, null, null, null, null, null, null,
      rows.isEmpty() ? null : rows.getFirst());
  }

  private List<OrderView> byId(Long orderId) {
    return jdbc.query("SELECT * FROM shop_orders WHERE id=?", this::order, orderId);
  }

  private OrderView order(ResultSet rs, int row) throws SQLException {
    return new OrderView(rs.getLong("id"), rs.getString("order_number"), rs.getLong("customer_id"),
      rs.getString("status"), rs.getString("inventory_status"), rs.getBigDecimal("total_amount"),
      rs.getString("currency"), instant(rs, "created_at"), instant(rs, "updated_at"),
      instant(rs, "confirmed_at"), instant(rs, "shipped_at"), rs.getString("carrier"),
      rs.getString("tracking_number"), instant(rs, "payment_due_at"), instant(rs, "cancelled_at"),
      rs.getString("cancel_reason"), instant(rs, "received_at"), instant(rs, "completed_at"),
      instant(rs, "logistics_delivered_at"));
  }

  private ItemView item(ResultSet rs, int row) throws SQLException {
    return new ItemView(rs.getLong("id"), rs.getLong("product_id"), rs.getLong("merchant_id"),
      rs.getString("product_name"), rs.getString("thumbnail_url"), rs.getBigDecimal("unit_price"),
      rs.getInt("quantity"), rs.getBigDecimal("subtotal"));
  }

  private AddressView address(ResultSet rs, int row) throws SQLException {
    return new AddressView(rs.getString("recipient_name"), rs.getString("phone"),
      rs.getString("country_code"), rs.getString("state_province"), rs.getString("city"),
      rs.getString("district"), rs.getString("address_line1"), rs.getString("address_line2"),
      rs.getString("postal_code"), rs.getString("label"));
  }

  private EventView eventView(ResultSet rs, int row) throws SQLException {
    return new EventView(rs.getLong("id"), nullableLong(rs, "actor_id"), rs.getString("event_type"),
      rs.getString("from_status"), rs.getString("to_status"), rs.getString("detail"),
      rs.getString("trigger_source"), nullableLong(rs, "scheduled_task_id"),
      instant(rs, "created_at"));
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private void requireCreate(CreateOrder value) {
    if (value == null || value.orderNumber() == null || value.orderNumber().isBlank()
        || value.customerId() == null || value.totalAmount() == null || value.currency() == null
        || value.paymentDueAt() == null || value.items() == null || value.items().isEmpty()
        || value.address() == null) {
      throw new IllegalArgumentException("Order create request is incomplete");
    }
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
