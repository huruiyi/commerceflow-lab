package lab.commerceflow.storefront.service;

import lab.commerceflow.storefront.domain.AppUser;
import lab.commerceflow.storefront.mapper.CartMapper;
import lab.commerceflow.storefront.mapper.ProductMapper;
import lab.commerceflow.storefront.web.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class CartService {
  private final CartMapper carts;
  private final ProductMapper products;

  public CartService(CartMapper carts, ProductMapper products) {
    this.carts = carts; this.products = products;
  }

  @Transactional
  public OrderResponse add(AppUser user, OrderRequest request) {
    if (products.selectById(request.getProductId()) == null) throw new ResourceNotFoundException("Product not found");
    carts.ensureCart(user.getId());
    carts.add(carts.cartId(user.getId()), request.getProductId(), request.getQuantity() == null ? 1 : request.getQuantity());
    return carts.list(user.getId()).stream().filter(i -> i.getProductName().equals(products.selectById(request.getProductId()).getName()))
        .findFirst().map(OrderResponse::from).orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));
  }

  public List<OrderResponse> list(AppUser user) { return carts.list(user.getId()).stream().map(OrderResponse::from).toList(); }

  @Transactional
  public OrderResponse quantity(AppUser user, Long id, QuantityUpdateRequest request) {
    if (carts.quantity(user.getId(), id, request.getQuantity()) == 0) throw new ResourceNotFoundException("Cart item not found");
    return carts.view(user.getId(), id).map(OrderResponse::from).orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));
  }

  @Transactional
  public void remove(AppUser user, Long id) {
    if (carts.remove(user.getId(), id) == 0) throw new ResourceNotFoundException("Cart item not found");
  }

  @Transactional public void clear(AppUser user) { carts.clear(user.getId()); }
}
