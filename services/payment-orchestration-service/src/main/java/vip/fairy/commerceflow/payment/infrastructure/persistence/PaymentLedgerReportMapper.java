package vip.fairy.commerceflow.payment.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaymentLedgerReportMapper {

  List<Aggregate> summarize(@Param("days") List<DayWindow> days,
                            @Param("startSecond") long startSecond, @Param("endSecond") long endSecond,
                            @Param("channel") String channel, @Param("currency") String currency,
                            @Param("merchantIds") List<Long> merchantIds);

  record DayWindow(LocalDate date, long startSecond, long endSecond) {
  }

  record Aggregate(LocalDate reportDate, String channel, String currency, String paymentStatus,
                   boolean unattributed,
                   long createdCount, long paidCount, BigDecimal receivedAmount, long refundCount,
                   BigDecimal refundedAmount, long missingPaidAtCount, long missingRefundSucceededAtCount) {
  }
}
