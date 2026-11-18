package vip.fairy.commerceflow.commerce;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.domain.NewOrder;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderReviewableItem;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;

final class TestOrderStoreAdapter implements OrderStorePort {

  private final JdbcTemplate jdbc;

  TestOrderStoreAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String status(Long id) {
    return jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?", String.class, id);
  }

  @Override
  public int markLogisticsDelivered(Long id, Instant deliveredAt) {
    return jdbc.update("UPDATE shop_orders SET logistics_delivered_at=COALESCE(logistics_delivered_at,?),"
      + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('SHIPPED','RECEIVED','COMPLETED')",
      deliveredAt, id);
  }

  @Override
  public int insertOrder(NewOrder order, List<OrderItemSnapshot> items,
                         OrderAddressSnapshot address, Long actorId) {
    GeneratedKeyHolder key = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      var statement = connection.prepareStatement("INSERT INTO shop_orders(order_number,customer_id,"
          + "status,inventory_status,total_amount,currency,payment_due_at,created_at,updated_at) "
          + "VALUES (?,?,'PENDING_PAYMENT','WAITING_RESERVATION',?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        java.sql.Statement.RETURN_GENERATED_KEYS);
      statement.setString(1, order.getOrderNumber());
      statement.setLong(2, order.getCustomerId());
      statement.setBigDecimal(3, order.getTotalAmount());
      statement.setString(4, order.getCurrency());
      statement.setTimestamp(5, Timestamp.from(order.getPaymentDueAt()));
      return statement;
    }, key);
    order.setId(key.getKey().longValue());
    for (OrderItemSnapshot item : items) {
      jdbc.update("INSERT INTO order_items(order_id,product_id,merchant_id,product_name,thumbnail_url,"
          + "unit_price,quantity,subtotal) VALUES (?,?,?,?,?,?,?,?)", order.getId(), item.productId(),
        item.merchantId(), item.productName(), item.thumbnailUrl(), item.unitPrice(), item.quantity(),
        item.subtotal());
    }
    jdbc.update("INSERT INTO order_shipping_addresses(order_id,recipient_name,phone,country_code,"
        + "state_province,city,district,address_line1,address_line2,postal_code,label) "
        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)", order.getId(), address.recipientName(), address.phone(),
      address.countryCode(), address.stateProvince(), address.city(), address.district(),
      address.addressLine1(), address.addressLine2(), address.postalCode(), address.label());
    insertEvent(order.getId(), actorId, "ORDER_CREATED", "CART", "PENDING_PAYMENT",
      "Checkout created from cart");
    return 1;
  }

  @Override
  public void insertEventDetailed(Long orderId, Long actorId, String type, String fromStatus,
                                  String toStatus, String detail, String triggerSource,
                                  Long scheduledTaskId) {
    jdbc.update("INSERT INTO order_events(order_id,actor_id,event_type,from_status,to_status,detail,"
        + "trigger_source,scheduled_task_id,created_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
      orderId, actorId, type, fromStatus, toStatus, detail, triggerSource, scheduledTaskId);
  }

  @Override
  public List<ShopOrderView> list(Long customerId) {
    return orders("SELECT * FROM shop_orders WHERE customer_id=? ORDER BY created_at DESC,id DESC",
      customerId);
  }

  @Override
  public Optional<ShopOrderView> find(Long customerId, Long id) {
    return orders("SELECT * FROM shop_orders WHERE id=? AND customer_id=?", id, customerId)
      .stream().findFirst();
  }

  @Override
  public Optional<ShopOrderView> lock(Long customerId, Long id) {
    return find(customerId, id);
  }

  @Override
  public Optional<ShopOrderView> lockById(Long id) {
    return orders("SELECT * FROM shop_orders WHERE id=?", id).stream().findFirst();
  }

  @Override
  public List<ShopOrderView> listMerchant(Long merchantId) {
    return orders("SELECT DISTINCT o.* FROM shop_orders o JOIN order_items i ON i.order_id=o.id "
      + "WHERE i.merchant_id=? ORDER BY o.created_at DESC", merchantId);
  }

  @Override
  public Optional<ShopOrderView> findMerchant(Long merchantId, Long orderId) {
    return orders("SELECT o.* FROM shop_orders o WHERE o.id=? AND EXISTS (SELECT 1 FROM order_items i "
      + "WHERE i.order_id=o.id AND i.merchant_id=?)", orderId, merchantId).stream().findFirst();
  }

  @Override
  public List<OrderItemView> items(Long orderId) {
    return jdbc.query("SELECT * FROM order_items WHERE order_id=? ORDER BY id",
      (rs, row) -> mapped(rs, OrderItemView.class), orderId);
  }

  @Override
  public OrderAddressView address(Long orderId) {
    List<OrderAddressView> rows = jdbc.query(
      "SELECT * FROM order_shipping_addresses WHERE order_id=?",
      (rs, row) -> mapped(rs, OrderAddressView.class), orderId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Override
  public List<OrderEventView> events(Long orderId) {
    return jdbc.query("SELECT id,actor_id,event_type,from_status,to_status,detail,trigger_source,"
        + "scheduled_task_id,created_at FROM order_events WHERE order_id=? ORDER BY created_at,id",
      (rs, row) -> mapped(rs, OrderEventView.class), orderId);
  }

  @Override
  public Long orderCustomerId(Long orderId) {
    return jdbc.queryForObject("SELECT customer_id FROM shop_orders WHERE id=?", Long.class, orderId);
  }

  @Override
  public Optional<OrderReviewableItem> reviewable(Long customerId, Long itemId) {
    return jdbc.query("SELECT i.id AS order_item_id,i.product_id FROM order_items i JOIN shop_orders o "
        + "ON o.id=i.order_id WHERE i.id=? AND o.customer_id=? AND o.status IN ('RECEIVED','COMPLETED')",
      (rs, row) -> new OrderReviewableItem(rs.getLong(1), rs.getLong(2)), itemId, customerId)
      .stream().findFirst();
  }

  @Override
  public int updateStatus(Long id, String fromStatus, String toStatus) {
    return jdbc.update("UPDATE shop_orders SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=?",
      toStatus, id, fromStatus);
  }

  @Override
  public int updateInventoryStatus(Long id, String fromStatus, String toStatus) {
    return jdbc.update("UPDATE shop_orders SET inventory_status=?,updated_at=CURRENT_TIMESTAMP "
      + "WHERE id=? AND inventory_status=?", toStatus, id, fromStatus);
  }

  @Override
  public int startInventoryCommit(Long id) {
    return jdbc.update("UPDATE shop_orders SET inventory_status='WAITING_COMMIT',"
      + "updated_at=CURRENT_TIMESTAMP WHERE id=? "
      + "AND inventory_status IN ('RESERVED','WAITING_RESERVATION')", id);
  }

  @Override
  public int startCancellation(Long id, String reason) {
    return jdbc.update("UPDATE shop_orders SET status='CANCELLING',cancel_reason=?,"
      + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING_PAYMENT'", reason, id);
  }

  @Override
  public int finishCancellation(Long id) {
    return jdbc.update("UPDATE shop_orders SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,"
      + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='CANCELLING'", id);
  }

  @Override
  public int restorePaidAfterCancellationRace(Long id) {
    return jdbc.update("UPDATE shop_orders SET status='PAID',cancel_reason=NULL,"
      + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='CANCELLING'", id);
  }

  @Override
  public int restorePaidAfterLateSuccess(Long id) {
    return jdbc.update("UPDATE shop_orders SET status='PAID',cancelled_at=NULL,"
      + "cancel_reason='Recovered after late successful payment',updated_at=CURRENT_TIMESTAMP "
      + "WHERE id=? AND status='CANCELLED'", id);
  }

  @Override
  public int markReceived(Long id, Instant receivedAt) {
    return jdbc.update("UPDATE shop_orders SET status='RECEIVED',received_at=?,"
      + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='SHIPPED'", receivedAt, id);
  }

  @Override
  public int markCompleted(Long id, Instant completedAt) {
    return jdbc.update("UPDATE shop_orders SET status='COMPLETED',completed_at=?,"
      + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RECEIVED'", completedAt, id);
  }

  @Override
  public int merchantConfirm(Long orderId, Long merchantId) {
    int changed = jdbc.update("UPDATE shop_orders SET status='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP,"
        + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PAID' AND EXISTS "
        + "(SELECT 1 FROM order_items WHERE order_id=shop_orders.id AND merchant_id=?)", orderId, merchantId);
    if (changed == 1) {
      insertEvent(orderId, merchantId, "MERCHANT_CONFIRMED", "PAID", "CONFIRMED", "Merchant accepted the order");
    }
    return changed;
  }

  @Override
  public int merchantShip(Long orderId, Long merchantId, String carrier, String tracking) {
    int changed = jdbc.update("UPDATE shop_orders SET status='SHIPPED',carrier=?,tracking_number=?,"
        + "shipped_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='CONFIRMED' "
        + "AND inventory_status='COMMITTED' AND EXISTS "
        + "(SELECT 1 FROM order_items WHERE order_id=shop_orders.id AND merchant_id=?)",
      carrier, tracking, orderId, merchantId);
    if (changed == 1) {
      insertEvent(orderId, merchantId, "ORDER_SHIPPED", "CONFIRMED", "SHIPPED", carrier + " / " + tracking);
    }
    return changed;
  }

  @Override
  public int updateShipment(Long orderId, String carrier, String tracking) {
    return jdbc.update("UPDATE shop_orders SET carrier=?,tracking_number=?,updated_at=CURRENT_TIMESTAMP "
      + "WHERE id=? AND status='SHIPPED'", carrier, tracking, orderId);
  }

  private List<ShopOrderView> orders(String sql, Object... arguments) {
    return jdbc.query(sql, (rs, row) -> mapped(rs, ShopOrderView.class), arguments);
  }

  private <T> T mapped(ResultSet result, Class<T> type) {
    try {
      T target = type.getDeclaredConstructor().newInstance();
      Map<String,Object> values = values(result);
      for (Field field : type.getDeclaredFields()) {
        Object value = values.get(snake(field.getName()));
        if (value instanceof Timestamp timestamp && field.getType() == Instant.class) {
          value = timestamp.toInstant();
        }
        if (value != null) {
          field.setAccessible(true);
          field.set(target, value);
        }
      }
      return target;
    } catch (Exception exception) {
      throw new IllegalStateException("Could not map test order row", exception);
    }
  }

  private Map<String,Object> values(ResultSet result) throws Exception {
    Map<String,Object> values = new HashMap<>();
    ResultSetMetaData metadata = result.getMetaData();
    for (int column = 1; column <= metadata.getColumnCount(); column++) {
      values.put(metadata.getColumnLabel(column).toLowerCase(), result.getObject(column));
    }
    return values;
  }

  private String snake(String value) {
    return value.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
  }
}
