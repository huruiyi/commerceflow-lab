package vip.fairy.commerceflow.commerce.cart.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.cart.application.contract.v1.RepurchaseCartV1;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CartMapper;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CartRepurchaseMapper;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CheckoutCartMapper;
import vip.fairy.commerceflow.commerce.catalog.application.CatalogProductLookup;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.StockAvailabilityQuery;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class CartRepurchaseService implements RepurchaseCartV1 {

  private static final int CART_ITEM_LIMIT = 99;

  private final CartMapper carts;
  private final CheckoutCartMapper checkout;
  private final CartRepurchaseMapper receipts;
  private final CatalogProductLookup catalog;
  private final StockAvailabilityQuery stocks;
  private final ObjectMapper json;

  public CartRepurchaseService(CartMapper carts, CheckoutCartMapper checkout, CartRepurchaseMapper receipts,
                               CatalogProductLookup catalog, StockAvailabilityQuery stocks, ObjectMapper json) {
    this.carts = carts;
    this.checkout = checkout;
    this.receipts = receipts;
    this.catalog = catalog;
    this.stocks = stocks;
    this.json = json;
  }

  @Override
  @Transactional
  public Result addOrderItems(Long customerId, Long orderId, UUID requestId, List<Item> items) {
    carts.ensureCart(customerId);
    String key = requestId.toString();
    var receipt = receipts.find(customerId, key);
    if (receipt != null) {
      if (!orderId.equals(receipt.orderId())) {
        throw new BusinessRuleException("This repurchase request belongs to another order");
      }
      return json.readValue(receipt.resultJson(), Result.class);
    }
    var existing = checkout.lockItems(customerId).stream()
      .collect(Collectors.toMap(CheckoutCartItem::getProductId, CheckoutCartItem::getQuantity));
    var requested = new LinkedHashMap<Long, Item>();
    items.forEach(item -> requested.merge(item.productId(), item,
      (a, b) -> new Item(a.productId(), a.originalName(), Math.addExact(a.quantity(), b.quantity()))));
    var availability = stocks.availability(requested.keySet());
    Long cartId = carts.cartId(customerId);
    List<AddedLine> added = new ArrayList<>();
    List<SkippedLine> skipped = new ArrayList<>();
    for (Item item : requested.values()) {
      CatalogProductLookup.ProductSnapshot product;
      try {
        product = catalog.require(item.productId());
      } catch (ResourceNotFoundException exception) {
        skipped.add(new SkippedLine(item.productId(), item.originalName(), item.quantity(), Reason.UNLISTED, null));
        continue;
      }
      int inCart = existing.getOrDefault(item.productId(), 0);
      var stock = availability.get(item.productId());
      var reason = unavailableReason(item.quantity(), inCart, stock);
      if (reason != null) {
        Integer available = stock == null || reason == Reason.UNLISTED ? null
          : Math.max(0, Math.min(CART_ITEM_LIMIT, stock.available()) - inCart);
        skipped.add(new SkippedLine(item.productId(), product.name(), item.quantity(), reason, available));
        continue;
      }
      carts.add(cartId, product.id(), product.merchantId(), product.name(), product.thumbnailUrl(), product.price(),
        item.quantity());
      added.add(new AddedLine(product.id(), product.name(), item.quantity(),
        inCart + item.quantity(), product.price()));
    }
    var result = new Result(added, skipped);
    receipts.insert(customerId, key, orderId, json.writeValueAsString(result));
    return result;
  }

  private Reason unavailableReason(int quantity, int inCart, StockAvailabilityQuery.Stock stock) {
    if (stock != null && !stock.listed()) return Reason.UNLISTED;
    if (stock == null || stock.available() == 0) return Reason.SOLD_OUT;
    if (quantity > CART_ITEM_LIMIT - inCart) return Reason.CART_LIMIT;
    return quantity > stock.available() - inCart ? Reason.INSUFFICIENT_STOCK : null;
  }
}
