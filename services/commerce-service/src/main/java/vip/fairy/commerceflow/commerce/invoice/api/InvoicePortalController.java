package vip.fairy.commerceflow.commerce.invoice.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.identity.application.contract.v1.InvoiceAccessV1;
import vip.fairy.commerceflow.commerce.invoice.application.CommerceInvoiceService;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.State;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.MerchantInvoice;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.OperatorInvoice;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.Page;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
@ConditionalOnProperty(prefix = "commerceflow.invoice", name = "enabled", havingValue = "true")
public class InvoicePortalController {

  private final CommerceInvoiceService invoices;
  private final CurrentActorIdResolver actors;
  private final InvoiceAccessV1 access;

  public InvoicePortalController(CommerceInvoiceService invoices, CurrentActorIdResolver actors,
                                 InvoiceAccessV1 access) {
    this.invoices = invoices;
    this.actors = actors;
    this.access = access;
  }

  @GetMapping("/api/invoices")
  @PreAuthorize("hasRole('CUSTOMER')")
  ResponseEntity<Page> customer(@ModelAttribute Filters filters, Authentication user) {
    return read(invoices.search(actors.requireId(user), null, filters.status(), filters.q(), filters.pageNumber()));
  }

  @GetMapping("/api/merchant/invoices")
  @PreAuthorize("hasRole('MERCHANT')")
  ResponseEntity<Page> merchant(@ModelAttribute Filters filters, Authentication user) {
    return read(invoices.search(null, access.requireMerchant(actors.requireId(user)), filters.status(), filters.q(),
      filters.pageNumber()));
  }

  @GetMapping("/api/merchant/invoices/{orderId}")
  @PreAuthorize("hasRole('MERCHANT')")
  ResponseEntity<MerchantInvoice> merchantDetail(@PathVariable long orderId, Authentication user) {
    return read(invoices.merchantInvoice(orderId, access.requireMerchant(actors.requireId(user))));
  }

  @GetMapping("/api/admin/invoices")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Page> platform(@ModelAttribute Filters filters, Authentication user) {
    access.requireOperator(actors.requireId(user));
    return read(invoices.search(null, null, filters.status(), filters.q(), filters.pageNumber()));
  }

  @GetMapping("/api/admin/invoices/{orderId}")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<OperatorInvoice> platformDetail(@PathVariable long orderId, Authentication user) {
    access.requireOperator(actors.requireId(user));
    return read(invoices.operatorInvoice(orderId));
  }

  @PostMapping("/api/admin/invoices/{orderId}/review-actions")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<OperatorInvoice> recheck(@PathVariable long orderId, @Valid @RequestBody Review command,
                                          Authentication user) {
    long actor = actors.requireId(user);
    access.requireOperator(actor);
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(
      invoices.recheck(orderId, actor, command.note()));
  }

  private static <T> ResponseEntity<T> read(T value) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
  }

  record Filters(State status, String q, Integer page) {

    int pageNumber() {
      return page == null ? 0 : page;
    }
  }

  record Review(@NotBlank @Size(max = 300) String note) {
  }
}
