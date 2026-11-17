package vip.fairy.commerceflow.commerce.payment.application.contract.v1;

import java.util.Optional;

public interface PaymentOrchestrationV1 {

  PaymentSnapshotV1 create(PaymentCreateCommandV1 command);

  Optional<PaymentSnapshotV1> latest(PaymentQueryV1 query);

  Optional<RefundSnapshotV1> refund(PaymentQueryV1 query);

  Optional<RefundSnapshotV1> refund(RefundQueryV1 query);

  RefundSnapshotV1 requestRefund(RefundCreateCommandV1 command);

  PaymentCloseResultV1 closePending(PaymentCloseCommandV1 command);
}
