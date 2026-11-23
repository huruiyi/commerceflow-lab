package vip.fairy.commerceflow.commerce.merchant.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.merchant.application.StatementDifferenceReviewService;
import vip.fairy.commerceflow.commerce.merchant.application.StatementDifferenceReviewService.Command;
import vip.fairy.commerceflow.commerce.merchant.application.StatementReconciliationService;
import vip.fairy.commerceflow.commerce.merchant.application.StatementReconciliationService.PeriodComparison;
import vip.fairy.commerceflow.commerce.merchant.application.StatementReconciliationService.Report;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceReview;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceReview.Decision;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceReview.Target;
import vip.fairy.commerceflow.commerce.shared.api.AdminListPage;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
public class MerchantReconciliationController {

  private final StatementReconciliationService reports;
  private final StatementDifferenceReviewService reviews;
  private final CurrentActorIdResolver actors;

  MerchantReconciliationController(StatementReconciliationService reports, StatementDifferenceReviewService reviews,
                                   CurrentActorIdResolver actors) {
    this.reports = reports;
    this.reviews = reviews;
    this.actors = actors;
  }

  @GetMapping("/api/admin/stores/{merchantId}/statements/{id}/reconciliation/reviews")
  @PreAuthorize("hasRole('ADMIN')")
  AdminListPage<StatementDifferenceReview> reviewHistory(@PathVariable long merchantId, @PathVariable UUID id,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
    return reviews.list(merchantId, id.toString(), page, size);
  }

  @PostMapping("/api/admin/stores/{merchantId}/statements/{id}/reconciliation/reviews")
  @PreAuthorize("hasRole('ADMIN')")
  StatementDifferenceReview recordReview(Authentication actor, @PathVariable long merchantId, @PathVariable UUID id,
                                          @RequestBody @Valid ReviewRequest request) {
    return reviews.record(merchantId, id.toString(), actors.requireId(actor),
      new Command(request.requestId(), request.page(), request.size(), request.target().toTarget(),
        request.decision(), request.reason()));
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

  @GetMapping("/api/admin/stores/{merchantId}/statements/{id}/period-comparison")
  @PreAuthorize("hasRole('ADMIN')")
  PeriodComparison adminPeriods(@PathVariable long merchantId, @PathVariable UUID id,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "10") int size) {
    return reports.comparePeriods(merchantId, id.toString(), page, size);
  }

  @GetMapping("/api/merchant/settlement/statements/{id}/period-comparison")
  @PreAuthorize("hasRole('MERCHANT')")
  PeriodComparison merchantPeriods(Authentication actor, @PathVariable UUID id,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "10") int size) {
    return reports.comparePeriods(actors.requireId(actor), id.toString(), page, size);
  }

  record ReviewRequest(@NotNull UUID requestId, @NotNull @Min(0) Integer page, @NotNull @Min(1) @Max(20) Integer size,
                       @NotNull @Valid ReviewTarget target, @NotNull Decision decision,
                       @NotBlank @Size(max = 300) String reason) {
  }

  record ReviewTarget(@Positive Long orderId, @Positive Long orderLineId, @NotBlank @Size(max = 64) String kind,
                      @NotBlank @Size(max = 128) String reference, BigDecimal expected, BigDecimal observed) {

    Target toTarget() {
      return new Target(orderId, orderLineId, kind, reference, expected, observed);
    }
  }
}
