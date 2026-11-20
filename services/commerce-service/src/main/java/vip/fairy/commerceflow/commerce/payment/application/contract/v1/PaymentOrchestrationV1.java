package vip.fairy.commerceflow.commerce.payment.application.contract.v1;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

public interface PaymentOrchestrationV1 {

  PaymentSnapshotV1 create(PaymentCreateCommandV1 command);

  Optional<PaymentSnapshotV1> latest(PaymentQueryV1 query);

  Optional<RefundSnapshotV1> refund(PaymentQueryV1 query);

  Optional<RefundSnapshotV1> refund(RefundQueryV1 query);

  RefundSnapshotV1 requestRefund(RefundCreateCommandV1 command);

  PaymentCloseResultV1 closePending(PaymentCloseCommandV1 command);

  JsonNode ledgerReport(LedgerReportQuery query);

  record LedgerReportQuery(LocalDate startDate, LocalDate endDate, String timeZone, PaymentChannelV1 channel,
                           String currency, List<Long> merchantIds) {
  }
}
