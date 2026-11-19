package vip.fairy.commerceflow.commerce.ordering.api;

import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
import vip.fairy.commerceflow.commerce.logistics.application.LogisticsService;
import vip.fairy.commerceflow.commerce.ordering.application.OrderCancellationService;
import vip.fairy.commerceflow.commerce.ordering.application.PurchaseOrderService;
import vip.fairy.commerceflow.commerce.ordering.application.CheckoutStockException;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListReadV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListPage;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderListQuery;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/purchase-orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class PurchaseOrderController {

  private final CurrentUserService currentUsers;
  private final PurchaseOrderService orders;
  private final LogisticsService logistics;
  private final OrderCancellationService cancellations;
  private final OrderListReadV1 search;

  public PurchaseOrderController(CurrentUserService currentUsers, PurchaseOrderService orders,
                                 OrderCancellationService cancellations, LogisticsService logistics,
                                 OrderListReadV1 search) {
    this.currentUsers = currentUsers;
    this.orders = orders;
    this.cancellations = cancellations;
    this.logistics = logistics;
    this.search = search;
  }

  @PostMapping("/checkout")
  ShopOrderResponse checkout(@Valid @RequestBody(required = false) CheckoutRequest request,
                             Authentication authentication) {
    AppUser user = currentUsers.require(authentication);
    return request == null ? orders.checkout(user)
      : orders.checkout(user, request.addressId(), request.expectedTotal(), request.itemIds());
  }

  @ExceptionHandler(CheckoutStockException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  Map<String, Object> checkoutStock(CheckoutStockException exception) {
    return Map.of("timestamp", Instant.now(), "status", HttpStatus.CONFLICT.value(),
      "code", "CHECKOUT_STOCK_CHANGED", "message", exception.getMessage(), "lines", exception.lines());
  }

  @GetMapping
  List<ShopOrderResponse> list(Authentication authentication) {
    return orders.list(currentUsers.require(authentication));
  }

  @GetMapping("/{id}")
  ShopOrderResponse get(@PathVariable Long id, Authentication authentication) {
    return orders.require(currentUsers.require(authentication), id);
  }

  @GetMapping("/search")
  OrderListPage<ShopOrderResponse> search(Authentication authentication,
                                         @RequestParam(defaultValue = "") String q,
                                         @RequestParam(defaultValue = "") String status,
                                         @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "10") int size,
                                         @RequestParam(defaultValue = "en") String lang,
                                         @RequestParam(defaultValue = "UTC") String zone) {
    var query = OrderListQuery.from(q, status, from, to, page, size, zone);
    return search.search(currentUsers.require(authentication).getId(), null, query, lang).map(orders::response);
  }

  @GetMapping("/{id}/logistics")
  LogisticsResponse logistics(@PathVariable Long id, Authentication authentication) {
    return logistics.require(currentUsers.require(authentication), id);
  }

  @PostMapping("/{id}/payments")
  ShopOrderResponse pay(@PathVariable Long id,
                        @Valid @RequestBody CreatePaymentRequest request,
                        Authentication authentication) {
    return orders.pay(currentUsers.require(authentication), id, request);
  }

  @PostMapping("/{id}/cancel")
  ShopOrderResponse cancel(@PathVariable Long id,
                           @Valid @RequestBody(required = false) CancelOrderRequest request,
                           Authentication authentication) {
    AppUser user = currentUsers.require(authentication);
    cancellations.cancelByCustomer(user, id, request == null ? null : request.reason());
    return orders.require(user, id);
  }
}
