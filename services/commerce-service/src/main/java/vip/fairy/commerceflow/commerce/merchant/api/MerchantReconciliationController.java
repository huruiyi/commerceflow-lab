package vip.fairy.commerceflow.commerce.merchant.api;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.merchant.application.StatementReconciliationService;
import vip.fairy.commerceflow.commerce.merchant.application.StatementReconciliationService.Report;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
public class MerchantReconciliationController {

  private final StatementReconciliationService reports;
  private final CurrentActorIdResolver actors;

  MerchantReconciliationController(StatementReconciliationService reports, CurrentActorIdResolver actors) {
    this.reports = reports;
    this.actors = actors;
  }

  @GetMapping("/api/admin/stores/{merchantId}/statements/{id}/reconciliation")
  @PreAuthorize("hasRole('ADMIN')")
  Report admin(@PathVariable long merchantId, @PathVariable UUID id,
               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    return reports.reconcile(merchantId, id.toString(), page, size);
  }

  @GetMapping("/api/merchant/settlement/statements/{id}/reconciliation")
  @PreAuthorize("hasRole('MERCHANT')")
  Report merchant(Authentication actor, @PathVariable UUID id,
                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    return reports.reconcile(actors.requireId(actor), id.toString(), page, size);
  }
}
