package lab.commerceflow.storefront.web;

import lab.commerceflow.storefront.domain.AppUser;
import lab.commerceflow.storefront.service.*;
import lab.commerceflow.storefront.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/cart")
@PreAuthorize("hasRole('CUSTOMER')")
public class CartController {
  private final CurrentUserService currentUsers; private final CartService cart;
  public CartController(CurrentUserService currentUsers, CartService cart) { this.currentUsers=currentUsers; this.cart=cart; }
  @GetMapping List<OrderResponse> list(Authentication a) { return cart.list(currentUsers.require(a)); }
  @PostMapping("/items") OrderResponse add(@Valid @RequestBody OrderRequest r, Authentication a) { return cart.add(currentUsers.require(a), r); }
  @PutMapping("/items/{id}") OrderResponse quantity(@PathVariable Long id, @Valid @RequestBody QuantityUpdateRequest r, Authentication a) { return cart.quantity(currentUsers.require(a), id, r); }
  @DeleteMapping("/items/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void remove(@PathVariable Long id, Authentication a) { cart.remove(currentUsers.require(a), id); }
  @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT) void clear(Authentication a) { cart.clear(currentUsers.require(a)); }
}
