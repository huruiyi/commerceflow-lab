package vip.fairy.commerceflow.commerce.refund.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.refund.application.ReturnWorkOrderService;
import vip.fairy.commerceflow.commerce.refund.domain.AfterSaleListQuery;
import vip.fairy.commerceflow.commerce.refund.domain.ReturnWorkOrder;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;

@RestController
@PreAuthorize("hasRole('MERCHANT')")
@RequestMapping("/api/merchant/return-work-orders")
public class ReturnWorkOrderController {

  private final ReturnWorkOrderService work;
  private final CurrentUserService users;

  ReturnWorkOrderController(ReturnWorkOrderService work, CurrentUserService users) {
    this.work = work;
    this.users = users;
  }

  @GetMapping
  AfterSaleListQuery.Page<ReturnWorkOrder> list(Authentication actor, @ModelAttribute AfterSaleListQuery query) {
    return work.list(users.require(actor).getId(), query);
  }

  @GetMapping("/{id}")
  ReturnWorkOrderService.Detail detail(Authentication actor, @PathVariable UUID id) {
    return work.detail(users.require(actor).getId(), id.toString());
  }

  @GetMapping("/{id}/evidence")
  ReturnWorkOrderService.Evidence evidence(Authentication actor, @PathVariable UUID id) {
    return work.evidence(users.require(actor).getId(), id.toString());
  }

  @PostMapping("/{id}/actions")
  ReturnWorkOrderService.Detail decide(Authentication actor, @PathVariable UUID id, @Valid @RequestBody Input input) {
    return work.decide(users.require(actor).getId(), id.toString(), input.requestId().toString(),
      input.action(), input.note(), input.quantity());
  }

  record Input(@NotNull UUID requestId, @NotBlank @Size(max = 24) String action,
               @NotBlank @Size(max = 300) String note, @Positive Integer quantity) {
  }
}
