package vip.fairy.commerceflow.payment.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.payment.application.contract.v1.InvoiceFundsV1;
import vip.fairy.commerceflow.payment.infrastructure.persistence.InvoiceFundsMapper;

import java.util.List;

@Service
public class InvoiceFundsQuery {

  private final InvoiceFundsMapper mapper;

  public InvoiceFundsQuery(InvoiceFundsMapper mapper) {
    this.mapper = mapper;
  }

  public InvoiceFundsV1 find(long orderId) {
    if (orderId <= 0) {
      throw new IllegalArgumentException("Order ID must be positive");
    }
    var rows = mapper.findByOrderId(orderId);
    int paymentCount = (int) rows.stream().map(InvoiceFundsMapper.Row::paymentNumber).distinct().count();
    if (paymentCount != 1) {
      return new InvoiceFundsV1(paymentCount, null, null, null, null, null, List.of());
    }
    var payment = rows.getFirst();
    var refunds = rows.stream().filter(row -> row.refundNumber() != null)
      .map(row -> new InvoiceFundsV1.Refund(row.refundNumber(), row.refundOrderId(), row.refundAmount(),
        row.refundCurrency(), row.succeededAt()))
      .toList();
    return new InvoiceFundsV1(paymentCount, payment.paymentNumber(), payment.paidAmount(), payment.refundedAmount(),
      payment.currency(), payment.paidAt(), refunds);
  }
}
