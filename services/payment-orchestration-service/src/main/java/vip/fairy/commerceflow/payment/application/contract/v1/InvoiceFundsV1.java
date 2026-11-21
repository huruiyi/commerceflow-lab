package vip.fairy.commerceflow.payment.application.contract.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Consistent read of the successful payment and its successful refunds. */
public record InvoiceFundsV1(
  int paymentCount,
  String paymentNumber,
  BigDecimal paidAmount,
  BigDecimal refundedAmount,
  String currency,
  Instant paidAt,
  List<Refund> refunds
) {

  public record Refund(String refundNumber, Long orderId, BigDecimal amount, String currency, Instant succeededAt) {
  }
}
