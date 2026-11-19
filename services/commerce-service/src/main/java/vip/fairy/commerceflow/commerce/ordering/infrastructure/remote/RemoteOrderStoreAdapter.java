package vip.fairy.commerceflow.commerce.ordering.infrastructure.remote;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderLineRefundV1;
import vip.fairy.commerceflow.commerce.ordering.domain.NewOrder;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemSnapshot;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListPage;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListQuery;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderReviewableItem;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;

@Component
public class RemoteOrderStoreAdapter implements OrderStorePort, OrderLineRefundV1 {

  private static final String CREATE = "/internal/v1/orders/store/create";
  private static final String QUERY = "/internal/v1/orders/store/query";
  private static final String MUTATIONS = "/internal/v1/orders/store/mutations";

  private final RestClient client;
  private final ObjectMapper mapper;
  private final OrderInternalRequestSigner signer;
  private final String baseUrl;

  public RemoteOrderStoreAdapter(RestClient.Builder builder, ObjectMapper mapper,
                                 OrderInternalRequestSigner signer,
                                 @Value("${commerceflow.order.remote.base-url:http://localhost:18089}")
                                 String baseUrl) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.client = builder.requestFactory(factory).build();
    this.mapper = mapper;
    this.signer = signer;
    this.baseUrl = baseUrl;
  }

  @Override
  public String status(Long id) {
    return query(new Query("STATUS", null, null, id, null)).status();
  }

  @Override
  public int markLogisticsDelivered(Long id, Instant deliveredAt) {
    return mutation("MARK_LOGISTICS_DELIVERED", id, null, null, null, null,
      null, null, null, null, null, null, deliveredAt);
  }

  @Override
  public int insertOrder(NewOrder order, List<OrderItemSnapshot> items,
                         OrderAddressSnapshot address, Long actorId) {
    CreateOrder request = new CreateOrder(order.getOrderNumber(), order.getCustomerId(),
      order.getTotalAmount(), order.getCurrency(), order.getPaymentDueAt(), actorId,
      items.stream().map(item -> new ItemSnapshot(item.productId(), item.merchantId(),
        item.productName(), item.thumbnailUrl(), item.unitPrice(), item.quantity(),
        item.subtotal(), item.discountAmount())).toList(),
      new AddressSnapshot(address.recipientName(), address.phone(), address.countryCode(),
        address.stateProvince(), address.city(), address.district(), address.addressLine1(),
        address.addressLine2(), address.postalCode(), address.label()),
      order.getShippingFee(), order.getDiscountAmount());
    ShopOrderView created = post(CREATE, request, ShopOrderView.class);
    order.setId(created.getId());
    order.setPaymentDueAt(created.getPaymentDueAt());
    return 1;
  }

  @Override
  public void insertEventDetailed(Long orderId, Long actorId, String type, String fromStatus,
                                  String toStatus, String detail, String triggerSource,
                                  Long scheduledTaskId) {
    mutation("INSERT_EVENT", orderId, actorId, fromStatus, toStatus, null, null, null,
      type, detail, triggerSource, scheduledTaskId, null);
  }

  @Override
  public List<ShopOrderView> list(Long customerId) {
    return safe(query(new Query("LIST_CUSTOMER", customerId, null, null, null)).orders());
  }

  @Override
  public Optional<ShopOrderView> find(Long customerId, Long id) {
    return Optional.ofNullable(query(new Query("FIND_CUSTOMER", customerId, null, id, null)).order());
  }

  @Override
  public Optional<ShopOrderView> lock(Long customerId, Long id) {
    return find(customerId, id);
  }

  @Override
  public Optional<ShopOrderView> lockById(Long id) {
    return Optional.ofNullable(query(new Query("FIND_BY_ID", null, null, id, null)).order());
  }

  @Override
  public List<ShopOrderView> listMerchant(Long merchantId) {
    return safe(query(new Query("LIST_MERCHANT", null, merchantId, null, null)).orders());
  }

  @Override
  public OrderListPage<ShopOrderView> search(Long customerId, Long merchantId, OrderListQuery criteria) {
    SearchResult result = post("/internal/v1/orders/store/search",
      new SearchRequest(customerId, merchantId, criteria), SearchResult.class);
    return new OrderListPage<>(result.orders(), result.total(), result.page(), result.size(),
      result.pendingConfirm(), result.pendingShip());
  }

  @Override
  public Optional<ShopOrderView> findMerchant(Long merchantId, Long orderId) {
    return Optional.ofNullable(query(new Query("FIND_MERCHANT", null, merchantId, orderId, null)).order());
  }

  @Override
  public List<OrderItemView> items(Long orderId) {
    return safe(query(new Query("ITEMS", null, null, orderId, null)).items());
  }

  @Override
  public OrderAddressView address(Long orderId) {
    return query(new Query("ADDRESS", null, null, orderId, null)).address();
  }

  @Override
  public List<OrderEventView> events(Long orderId) {
    return safe(query(new Query("EVENTS", null, null, orderId, null)).events());
  }

  @Override
  public Long orderCustomerId(Long orderId) {
    return query(new Query("ORDER_CUSTOMER", null, null, orderId, null)).customerId();
  }

  @Override
  public Optional<OrderReviewableItem> reviewable(Long customerId, Long itemId) {
    return Optional.ofNullable(query(new Query("REVIEWABLE", customerId, null, null, itemId))
      .reviewableItem());
  }

  @Override
  public int updateStatus(Long id, String fromStatus, String toStatus) {
    return mutation("UPDATE_STATUS", id, null, fromStatus, toStatus, null, null, null,
      null, null, null, null, null);
  }

  @Override
  public int updateInventoryStatus(Long id, String fromStatus, String toStatus) {
    return mutation("UPDATE_INVENTORY_STATUS", id, null, fromStatus, toStatus, null,
      null, null, null, null, null, null, null);
  }

  @Override
  public int startInventoryCommit(Long id) {
    return simple("START_INVENTORY_COMMIT", id);
  }

  @Override
  public int startCancellation(Long id, String reason) {
    return mutation("START_CANCELLATION", id, null, null, null, reason, null, null,
      null, null, null, null, null);
  }

  @Override
  public int finishCancellation(String mutationId, Long id) {
    return apply(new Mutation(mutationId, "FINISH_CANCELLATION", id, null,
      null, null, null, null, null, null, null, null, null, null));
  }

  @Override
  public void insertCancellationEvent(String mutationId, Long id, Long actorId, String reason,
                                      String triggerSource, Long scheduledTaskId) {
    apply(new Mutation(mutationId, "INSERT_EVENT", id, actorId, "CANCELLING", "CANCELLED",
      null, null, null, "ORDER_CANCELLED", reason, triggerSource, scheduledTaskId, null));
  }

  @Override
  public int restorePaidAfterCancellationRace(Long id) {
    return simple("RESTORE_PAID_CANCELLATION_RACE", id);
  }

  @Override
  public int restorePaidAfterLateSuccess(Long id) {
    return simple("RESTORE_PAID_LATE_SUCCESS", id);
  }

  @Override
  public int markReceived(Long id, Instant receivedAt) {
    return mutation("MARK_RECEIVED", id, null, null, null, null, null, null,
      null, null, null, null, receivedAt);
  }

  @Override
  public int markCompleted(Long id, Instant completedAt) {
    return mutation("MARK_COMPLETED", id, null, null, null, null, null, null,
      null, null, null, null, completedAt);
  }

  @Override
  public int merchantConfirm(String commandId, Long orderId, Long merchantId) {
    return apply(new Mutation(commandId, "MERCHANT_CONFIRM", orderId, merchantId, null, null,
      null, null, null, null, null, null, null, null));
  }

  @Override
  public int reserveShipment(String commandId, Long orderId, Long merchantId, String carrier, String tracking,
                             boolean unreservedOnly) {
    String type = unreservedOnly ? "RESERVE_NEW_SHIPMENT" : "RESERVE_SHIPMENT";
    return apply(new Mutation(commandId, type, orderId, merchantId, null, null, null, carrier,
      tracking, null, null, null, null, null));
  }

  @Override
  public int merchantShip(String commandId, Long orderId, Long merchantId, String carrier, String tracking) {
    return apply(new Mutation(commandId, "MERCHANT_SHIP", orderId, merchantId, null, null, null, carrier,
      tracking, null, null, null, null, null));
  }

  @Override
  public int updateShipment(String commandId, Long orderId, Long merchantId, String carrier, String tracking,
                            long version, String detail) {
    return apply(new Mutation(commandId, "UPDATE_SHIPMENT", orderId, merchantId,
      null, null, null, carrier, tracking, null, detail, null, null, null, version));
  }

  @Override
  public boolean apply(OrderLineRefundV1.Command command) {
    return post("/internal/v1/orders/store/line-refunds", command, Boolean.class);
  }

  private int simple(String type, Long orderId) {
    return mutation(type, orderId, null, null, null, null, null, null,
      null, null, null, null, null);
  }

  private int mutation(String type, Long orderId, Long actorId, String fromStatus,
                       String toStatus, String reason, String carrier, String tracking,
                       String eventType, String detail, String triggerSource,
                       Long scheduledTaskId, Instant occurredAt) {
    String mutationId = UUID.randomUUID().toString();
    return apply(new Mutation(mutationId, type, orderId, actorId,
      fromStatus, toStatus, reason, carrier, tracking, eventType, detail, triggerSource,
      scheduledTaskId, occurredAt));
  }

  private int apply(Mutation mutation) {
    return post(MUTATIONS, mutation, MutationResult.class).changed();
  }

  private QueryResult query(Query query) {
    return post(QUERY, query, QueryResult.class);
  }

  private <T> T post(String target, Object body, Class<T> responseType) {
    try {
      String payload = mapper.writeValueAsString(body);
      T response = client.post().uri(URI.create(baseUrl + target))
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> signer.sign(headers, target, payload))
        .body(payload).retrieve().body(responseType);
      if (response == null) throw new IllegalStateException("Order service returned no response");
      return response;
    } catch (RuntimeException exception) {
      if (exception instanceof OrderRemoteUnavailableException) throw exception;
      throw new OrderRemoteUnavailableException(exception);
    }
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private record CreateOrder(String orderNumber, Long customerId, BigDecimal totalAmount,
                             String currency, Instant paymentDueAt, Long actorId,
                             List<ItemSnapshot> items, AddressSnapshot address,
                             BigDecimal shippingFee, BigDecimal discountAmount) {
  }

  private record ItemSnapshot(Long productId, Long merchantId, String productName,
                              String thumbnailUrl, BigDecimal unitPrice, int quantity,
                              BigDecimal subtotal, BigDecimal discountAmount) {
  }

  private record AddressSnapshot(String recipientName, String phone, String countryCode,
                                 String stateProvince, String city, String district,
                                 String addressLine1, String addressLine2, String postalCode,
                                 String label) {
  }

  private record Query(String type, Long customerId, Long merchantId, Long orderId,
                       Long itemId) {
  }

  private record SearchRequest(Long customerId, Long merchantId, OrderListQuery criteria) {
  }

  private record SearchResult(List<ShopOrderView> orders, long total, int page, int size,
                              long pendingConfirm, long pendingShip) {
  }

  private record QueryResult(boolean found, String status, Long customerId, ShopOrderView order,
                             List<ShopOrderView> orders, List<OrderItemView> items,
                             OrderAddressView address, List<OrderEventView> events,
                             OrderReviewableItem reviewableItem) {
  }

  private record Mutation(String mutationId, String type, Long orderId, Long actorId,
                          String fromStatus, String toStatus, String reason, String carrier,
                          String trackingNumber, String eventType, String detail,
                          String triggerSource, Long scheduledTaskId, Instant occurredAt,
                          Long expectedShipmentVersion) {

    private Mutation(String id, String type, Long orderId, Long actorId, String from, String to, String reason,
                     String carrier, String tracking, String event, String detail, String source,
                     Long taskId, Instant occurredAt) {
      this(id, type, orderId, actorId, from, to, reason, carrier, tracking, event, detail, source, taskId,
        occurredAt, null);
    }
  }

  private record MutationResult(int changed, String previousStatus) {
  }
}
