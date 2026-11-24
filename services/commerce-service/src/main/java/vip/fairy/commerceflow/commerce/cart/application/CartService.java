package vip.fairy.commerceflow.commerce.cart.application;

import vip.fairy.commerceflow.commerce.catalog.application.CatalogProductLookup;
import vip.fairy.commerceflow.commerce.cart.api.OrderRequest;
import vip.fairy.commerceflow.commerce.cart.api.OrderResponse;
import vip.fairy.commerceflow.commerce.cart.api.QuantityUpdateRequest;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CartMapper;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CartService {

  private final CartMapper carts;
  private final CatalogProductLookup products;

  public CartService(CartMapper carts, CatalogProductLookup products) {
    this.carts = carts;
    this.products = products;
  }

  @Transactional
  public OrderResponse add(AppUser user, OrderRequest request) {
    CatalogProductLookup.ProductSnapshot product = request.getSkuId() == null
      ? products.require(request.getProductId()) : products.requireSku(request.getProductId(), request.getSkuId());
    carts.ensureCart(user.getId());
    Long cartId = carts.cartId(user.getId());
    carts.add(
      cartId,
      product.id(),
      product.merchantId(),
      product.name(),
      product.thumbnailUrl(),
      product.price(),
      request.getQuantity() == null ? 1 : request.getQuantity());
    return carts.viewByProductId(cartId, product.id())
      .map(OrderResponse::from)
      .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));
  }

  public List<CheckoutCartItem> priceItems(List<CheckoutCartItem> items) {
    var snapshot = products.snapshots(items.stream().map(CheckoutCartItem::getProductId).distinct().toList());
    return items.stream().map(item -> {
      var product = snapshot.get(item.getProductId());
      if (product == null) {
        throw new ResourceNotFoundException("Active product not found");
      }
      return item.withProductFacts(product.merchantId(), product.name(), product.thumbnailUrl(), product.price());
    }).toList();
  }

  public List<OrderResponse> list(AppUser user) {
    return carts.list(user.getId()).stream().map(OrderResponse::from).toList();
  }

  @Transactional
  public OrderResponse quantity(AppUser user, Long id, QuantityUpdateRequest request) {
    if (carts.quantity(user.getId(), id, request.getQuantity()) == 0) {
      throw new ResourceNotFoundException("Cart item not found");
    }
    return carts.view(user.getId(), id)
      .map(OrderResponse::from)
      .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));
  }

  @Transactional
  public void remove(AppUser user, Long id) {
    if (carts.remove(user.getId(), id) == 0) {
      throw new ResourceNotFoundException("Cart item not found");
    }
  }

  @Transactional
  public void clear(AppUser user) {
    carts.clear(user.getId());
  }
}
