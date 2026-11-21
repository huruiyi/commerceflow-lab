package vip.fairy.commerceflow.payment.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InvoiceFundsMapper {

  List<Row> findByOrderId(long orderId);

  record Row(String paymentNumber, BigDecimal paidAmount, BigDecimal refundedAmount, String currency,
             Instant paidAt, String refundNumber, Long refundOrderId, BigDecimal refundAmount,
             String refundCurrency, Instant succeededAt) {
  }
}
