package vip.fairy.commerceflow.commerce.merchant.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.merchant.application.MerchantStoreService;
import vip.fairy.commerceflow.commerce.merchant.domain.MerchantStore;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
public class MerchantStoreController {

  private final MerchantStoreService stores;
  private final CurrentActorIdResolver actors;

  public MerchantStoreController(MerchantStoreService stores, CurrentActorIdResolver actors) {
    this.stores = stores;
    this.actors = actors;
  }

  @GetMapping("/api/stores/{id}")
  PublicStore publicStore(@PathVariable Long id) {
    var store = stores.publicStore(id);
    return new PublicStore(store.merchantId(), store.name(), store.description());
  }

  @GetMapping("/api/stores")
  List<PublicStore> publicStores() {
    return stores.list().stream().filter(store -> "ACTIVE".equals(store.status()))
      .map(store -> new PublicStore(store.merchantId(), store.name(), store.description())).toList();
  }

  @GetMapping("/api/merchant/store")
  @PreAuthorize("hasRole('MERCHANT')")
  MerchantStore own(Authentication user) {
    return stores.find(actors.requireId(user));
  }

  @PutMapping("/api/merchant/store")
  @PreAuthorize("hasRole('MERCHANT')")
  MerchantStore edit(Authentication user, @Valid @RequestBody Profile input) {
    return stores.edit(actors.requireId(user), input.name(), input.description() == null ? "" : input.description());
  }

  @GetMapping("/api/admin/stores")
  @PreAuthorize("hasRole('ADMIN')")
  List<MerchantStore> list() {
    return stores.list();
  }

  @PutMapping("/api/admin/stores/{id}/status")
  @PreAuthorize("hasRole('ADMIN')")
  MerchantStore review(@PathVariable Long id, Authentication user, @Valid @RequestBody Decision input) {
    return stores.decide(id, actors.requireId(user), input.status());
  }

  record PublicStore(Long merchantId, String name, String description) {
  }

  record Profile(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description) {
  }

  record Decision(@NotBlank @Size(max = 16) String status) {
  }
}
