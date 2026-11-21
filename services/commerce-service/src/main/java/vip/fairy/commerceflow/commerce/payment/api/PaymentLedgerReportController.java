package vip.fairy.commerceflow.commerce.payment.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vip.fairy.commerceflow.commerce.identity.application.contract.v1.PaymentReportAccessV1;
import vip.fairy.commerceflow.commerce.identity.application.contract.v1.PaymentReportAccessV1.MerchantOption;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentChannelV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
public class PaymentLedgerReportController {

  private final CurrentActorIdResolver actors;
  private final PaymentReportAccessV1 access;
  private final PaymentOrchestrationV1 reports;

  public PaymentLedgerReportController(CurrentActorIdResolver actors, PaymentReportAccessV1 access,
                                       PaymentOrchestrationV1 reports) {
    this.actors = actors;
    this.access = access;
    this.reports = reports;
  }

  @GetMapping("/api/admin/payment-ledger-reports")
  ResponseEntity<JsonNode> platform(Authentication user, @ModelAttribute Filters filters) {
    return report(filters, access.platform(actors.requireId(user), filters.merchantId()));
  }

  @GetMapping("/api/merchant/payment-ledger-reports")
  ResponseEntity<JsonNode> merchant(Authentication user, @ModelAttribute Filters filters) {
    return report(filters, access.merchant(actors.requireId(user), filters.merchantId()));
  }

  @GetMapping("/api/admin/payment-ledger-reports/merchants")
  ResponseEntity<List<MerchantOption>> platformMerchants(Authentication user) {
    return merchantOptions(user, true);
  }

  @GetMapping("/api/merchant/payment-ledger-reports/merchants")
  ResponseEntity<List<MerchantOption>> ownMerchant(Authentication user) {
    return merchantOptions(user, false);
  }

  private ResponseEntity<List<MerchantOption>> merchantOptions(Authentication user, boolean platform) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
      .body(access.merchantOptions(actors.requireId(user), platform));
  }

  private ResponseEntity<JsonNode> report(Filters filters, List<Long> authorizedMerchants) {
    var query = new PaymentOrchestrationV1.LedgerReportQuery(filters.startDate(), filters.endDate(), filters.timeZone(),
      filters.channel(), filters.currency(), authorizedMerchants);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.ledgerReport(query));
  }

  record Filters(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                 String timeZone, PaymentChannelV1 channel, String currency, Long merchantId) {
  }
}
