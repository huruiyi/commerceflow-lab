package vip.fairy.commerceflow.order.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.order.application.OrderStoreService;
import vip.fairy.commerceflow.order.application.OrderLineRefundService;
import vip.fairy.commerceflow.order.application.contract.v1.OrderSearchV1;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.CreateOrder;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Mutation;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.MutationResult;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.MutationType;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.QueryType;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.OrderView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Query;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.QueryResult;
import vip.fairy.commerceflow.order.infrastructure.OrderShadowProperties;

@RestController
@RequestMapping("/internal/v1/orders/store")
public class OrderStoreV1Controller {

  private static final Set<MutationType> FULFILLMENT_MUTATIONS = Set.of(MutationType.MERCHANT_CONFIRM,
    MutationType.RESERVE_SHIPMENT, MutationType.RESERVE_NEW_SHIPMENT,
    MutationType.MERCHANT_SHIP, MutationType.UPDATE_SHIPMENT);

  private final OrderStoreService store;
  private final OrderLineRefundService lineRefunds;
  private final OrderShadowProperties properties;
  private final ObjectMapper mapper;

  public OrderStoreV1Controller(OrderStoreService store, OrderShadowProperties properties,
                                ObjectMapper mapper, OrderLineRefundService lineRefunds) {
    this.store = store;
    this.lineRefunds = lineRefunds;
    this.properties = properties;
    this.mapper = mapper;
  }

  @PostMapping("/create")
  OrderView create(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    return store.create(mapper.readValue(payload, CreateOrder.class));
  }

  @PostMapping("/query")
  QueryResult query(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    Query query = mapper.readValue(payload, Query.class);
    if (isFulfillment(request) && query.type() != QueryType.FIND_MERCHANT
        && query.type() != QueryType.SHIPMENT_LINES) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fulfillment may only query merchant orders");
    }
    return store.query(query);
  }

  @PostMapping("/mutations")
  MutationResult mutate(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    Mutation mutation = mapper.readValue(payload, Mutation.class);
    if (isFulfillment(request) && (mutation.type() == null || !FULFILLMENT_MUTATIONS.contains(mutation.type()))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Mutation is outside the fulfillment boundary");
    }
    return store.mutate(mutation);
  }

  @PostMapping("/search")
  OrderSearchV1.Result search(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    if (!"commerce-service".equals(request.getHeader("X-CommerceFlow-Service"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return store.search(mapper.readValue(payload, OrderSearchV1.Request.class));
  }

  @PostMapping("/line-refunds")
  boolean lineRefund(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    if (!"commerce-service".equals(request.getHeader("X-CommerceFlow-Service"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return lineRefunds.apply(mapper.readValue(payload, OrderLineRefundService.Command.class));
  }

  private boolean isFulfillment(HttpServletRequest request) {
    return "fulfillment-service".equals(request.getHeader("X-CommerceFlow-Service"));
  }

  private void requireTrafficAndHash(String payload, HttpServletRequest request) {
    if (!properties.acceptingTraffic()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Order cutover gate is closed; shadow replication remains available");
    }
    try {
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(StandardCharsets.UTF_8)));
      if (!actual.equals(request.getHeader("X-CommerceFlow-Content-SHA256"))) {
        throw new IllegalArgumentException("Order store payload hash is invalid");
      }
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
