package vip.fairy.commerceflow.commerce.cart.api;

import vip.fairy.commerceflow.commerce.cart.application.CartService;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
@PreAuthorize("hasRole('CUSTOMER')")
public class CartController {
  private final CurrentUserService currentUsers;
  private final CartService cart;

  public CartController(CurrentUserService currentUsers, CartService cart) {
    this.currentUsers = currentUsers;
    this.cart = cart;
  }

  @GetMapping
  List<OrderResponse> list(Authentication authentication) {
    return cart.list(currentUsers.require(authentication));
  }

  @PostMapping("/items")
  OrderResponse add(@Valid @RequestBody OrderRequest request, Authentication authentication) {
    return cart.add(currentUsers.require(authentication), request);
  }

  @PutMapping("/items/{id}")
  OrderResponse quantity(@PathVariable Long id,
                         @Valid @RequestBody QuantityUpdateRequest request,
                         Authentication authentication) {
    return cart.quantity(currentUsers.require(authentication), id, request);
  }

  @DeleteMapping("/items/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@PathVariable Long id, Authentication authentication) {
    cart.remove(currentUsers.require(authentication), id);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void clear(Authentication authentication) {
    cart.clear(currentUsers.require(authentication));
  }
}
