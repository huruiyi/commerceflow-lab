package vip.fairy.commerceflow.commerce.invoice.application.contract.v1;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface SettlementInvoiceV1 {

  Optional<Tickets> confirmed(long merchantId, long orderId);

  record Tickets(Ticket issue, List<String> includedRefunds, List<Ticket> reds) {
  }

  record Ticket(String invoiceNumber, String originalInvoiceNumber, String reference, String paymentNumber,
                BigDecimal amount, String currency) {
  }
}
