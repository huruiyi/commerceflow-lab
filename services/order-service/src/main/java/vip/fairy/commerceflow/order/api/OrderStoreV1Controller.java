package vip.fairy.commerceflow.order.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.order.application.OrderStoreService;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.CreateOrder;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Mutation;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.MutationResult;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.OrderView;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.Query;
import vip.fairy.commerceflow.order.application.contract.v1.OrderStoreV1.QueryResult;
import vip.fairy.commerceflow.order.infrastructure.OrderShadowProperties;

@RestController
@RequestMapping("/internal/v1/orders/store")
public class OrderStoreV1Controller {

  private final OrderStoreService store;
  private final OrderShadowProperties properties;
  private final ObjectMapper mapper;

  public OrderStoreV1Controller(OrderStoreService store, OrderShadowProperties properties,
                                ObjectMapper mapper) {
    this.store = store;
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
    return store.query(mapper.readValue(payload, Query.class));
  }

  @PostMapping("/mutations")
  MutationResult mutate(@RequestBody String payload, HttpServletRequest request) {
    requireTrafficAndHash(payload, request);
    return store.mutate(mapper.readValue(payload, Mutation.class));
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
