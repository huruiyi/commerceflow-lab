package vip.fairy.commerceflow.commerce.invoice.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.invoice.application.CommerceInvoiceService;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.View;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.Eligibility;

@RestController
@RequestMapping("/api/purchase-orders/{orderId}/invoices")
@PreAuthorize("hasRole('CUSTOMER')")
@ConditionalOnProperty(prefix = "commerceflow.invoice", name = "enabled", havingValue = "true")
public class CustomerInvoiceController {

  private final CurrentUserService users;
  private final CommerceInvoiceService invoices;

  public CustomerInvoiceController(CurrentUserService users, CommerceInvoiceService invoices) {
    this.users = users;
    this.invoices = invoices;
  }

  @PostMapping
  ResponseEntity<View> request(@PathVariable long orderId, Authentication authentication) {
    View view = invoices.request(orderId, users.require(authentication).getId());
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(view);
  }

  @GetMapping("/eligibility")
  ResponseEntity<Eligibility> eligibility(@PathVariable long orderId, Authentication authentication) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
      .body(invoices.eligibility(orderId, users.requireId(authentication)));
  }

  @GetMapping
  ResponseEntity<View> get(@PathVariable long orderId, Authentication authentication) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
      .body(invoices.get(orderId, users.require(authentication).getId()));
  }
}
