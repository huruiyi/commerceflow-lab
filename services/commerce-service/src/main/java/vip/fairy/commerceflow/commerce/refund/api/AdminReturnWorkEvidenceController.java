package vip.fairy.commerceflow.commerce.refund.api;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.refund.application.ReturnWorkOrderService;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/stores/{merchantId}/return-work-orders/{caseId}")
public class AdminReturnWorkEvidenceController {

  private final ReturnWorkOrderService work;

  AdminReturnWorkEvidenceController(ReturnWorkOrderService work) {
    this.work = work;
  }

  @GetMapping
  ReturnWorkOrderService.Detail detail(@PathVariable long merchantId, @PathVariable UUID caseId) {
    return work.detail(merchantId, caseId.toString());
  }

  @GetMapping("/evidence")
  ReturnWorkOrderService.Evidence evidence(@PathVariable long merchantId, @PathVariable UUID caseId) {
    return work.readEvidence(merchantId, caseId.toString());
  }
}
