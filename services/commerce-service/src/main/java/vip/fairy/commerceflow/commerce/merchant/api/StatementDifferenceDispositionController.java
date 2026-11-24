package vip.fairy.commerceflow.commerce.merchant.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.merchant.application.StatementDifferenceDispositionService;
import vip.fairy.commerceflow.commerce.merchant.domain.StatementDifferenceDisposition.View;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/stores/{merchantId}/statements/{statement}/reconciliation/reviews/{review}/disposition")
public class StatementDifferenceDispositionController {

  private final StatementDifferenceDispositionService dispositions;
  private final CurrentActorIdResolver actors;

  StatementDifferenceDispositionController(StatementDifferenceDispositionService dispositions,
                                            CurrentActorIdResolver actors) {
    this.dispositions = dispositions;
    this.actors = actors;
  }

  @GetMapping
  View get(@PathVariable long merchantId, @PathVariable UUID statement, @PathVariable UUID review) {
    return dispositions.get(merchantId, statement.toString(), review.toString());
  }

  @PostMapping("/receipts")
  View link(Authentication actor, @PathVariable long merchantId, @PathVariable UUID statement,
            @PathVariable UUID review, @RequestBody @Valid ReceiptRequest request) {
    return dispositions.link(merchantId, statement.toString(), review.toString(), actors.requireId(actor),
      request.requestId().toString(), request.receipt().strip());
  }

  @PostMapping("/closure")
  View close(Authentication actor, @PathVariable long merchantId, @PathVariable UUID statement,
             @PathVariable UUID review, @RequestBody @Valid ClosureRequest request) {
    return dispositions.close(merchantId, statement.toString(), review.toString(), actors.requireId(actor),
      request.requestId().toString(), request.reason().strip());
  }

  record ReceiptRequest(@NotNull UUID requestId, @NotBlank @Size(max = 128) String receipt) {
  }

  record ClosureRequest(@NotNull UUID requestId, @NotBlank @Size(max = 300) String reason) {
  }
}
