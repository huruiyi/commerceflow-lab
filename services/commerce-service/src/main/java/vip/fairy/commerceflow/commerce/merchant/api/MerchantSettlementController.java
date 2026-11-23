package vip.fairy.commerceflow.commerce.merchant.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import vip.fairy.commerceflow.commerce.shared.api.AdminListPage;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.merchant.application.MerchantSettlementService;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Account;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Entry;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Statement;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
public class MerchantSettlementController {

  private final MerchantSettlementService settlements;
  private final CurrentActorIdResolver actors;

  public MerchantSettlementController(MerchantSettlementService settlements, CurrentActorIdResolver actors) {
    this.settlements = settlements;
    this.actors = actors;
  }

  @PutMapping("/api/admin/stores/{merchantId}/settlement")
  @PreAuthorize("hasRole('ADMIN')")
  Account enable(@PathVariable Long merchantId, @Valid @RequestBody Enable form) {
    return settlements.enable(merchantId, form.commissionBps());
  }

  @PostMapping("/api/admin/stores/{merchantId}/statements")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Statement> close(@PathVariable Long merchantId, @Valid @RequestBody Close form,
                                  Authentication user) {
    var value = settlements.close(merchantId, form.requestId().toString(), actors.requireId(user));
    return value == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(value);
  }

  @GetMapping("/api/admin/stores/{merchantId}/statements")
  @PreAuthorize("hasRole('ADMIN')")
  List<Statement> adminStatements(@PathVariable Long merchantId) {
    return settlements.statements(merchantId);
  }

  @GetMapping("/api/admin/stores/{merchantId}/settlement/pending")
  @PreAuthorize("hasRole('ADMIN')")
  List<Entry> adminPending(@PathVariable Long merchantId) {
    return settlements.pending(merchantId);
  }

  @GetMapping("/api/admin/stores/{merchantId}/statements/{id}/entries")
  @PreAuthorize("hasRole('ADMIN')")
  List<Entry> adminEntries(@PathVariable Long merchantId, @PathVariable UUID id) {
    return settlements.entries(merchantId, id.toString());
  }

  @GetMapping("/api/merchant/settlement/statements")
  @PreAuthorize("hasRole('MERCHANT')")
  List<Statement> ownStatements(Authentication user) {
    return settlements.statements(actors.requireId(user));
  }

  @GetMapping("/api/merchant/settlement/pending")
  @PreAuthorize("hasRole('MERCHANT')")
  List<Entry> ownPending(Authentication user) {
    return settlements.pending(actors.requireId(user));
  }

  @GetMapping("/api/merchant/settlement/statements/{id}/entries")
  @PreAuthorize("hasRole('MERCHANT')")
  List<Entry> ownEntries(@PathVariable UUID id, Authentication user) {
    return settlements.entries(actors.requireId(user), id.toString());
  }

  @GetMapping("/api/admin/stores/{merchantId}/statements/page")
  @PreAuthorize("hasRole('ADMIN')")
  AdminListPage<Statement> adminStatementPage(@PathVariable Long merchantId,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    return settlements.statementPage(merchantId, page, size);
  }

  @GetMapping("/api/admin/stores/{merchantId}/settlement/pending/page")
  @PreAuthorize("hasRole('ADMIN')")
  AdminListPage<Entry> adminPendingPage(@PathVariable Long merchantId,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    return settlements.pendingPage(merchantId, page, size);
  }

  @GetMapping("/api/admin/stores/{merchantId}/statements/{id}/entries/page")
  @PreAuthorize("hasRole('ADMIN')")
  AdminListPage<Entry> adminEntryPage(@PathVariable Long merchantId, @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    return settlements.entryPage(merchantId, id.toString(), page, size);
  }

  @GetMapping("/api/merchant/settlement/statements/page")
  @PreAuthorize("hasRole('MERCHANT')")
  AdminListPage<Statement> ownStatementPage(Authentication user,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    return settlements.statementPage(actors.requireId(user), page, size);
  }

  @GetMapping("/api/merchant/settlement/pending/page")
  @PreAuthorize("hasRole('MERCHANT')")
  AdminListPage<Entry> ownPendingPage(Authentication user,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    return settlements.pendingPage(actors.requireId(user), page, size);
  }

  @GetMapping("/api/merchant/settlement/statements/{id}/entries/page")
  @PreAuthorize("hasRole('MERCHANT')")
  AdminListPage<Entry> ownEntryPage(Authentication user, @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    return settlements.entryPage(actors.requireId(user), id.toString(), page, size);
  }

  record Enable(@NotNull @Min(0) @Max(10000) Integer commissionBps) {
  }

  record Close(@NotNull UUID requestId) {
  }
}
