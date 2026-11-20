package vip.fairy.commerceflow.payment.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentChannelV1;
import vip.fairy.commerceflow.payment.infrastructure.persistence.PaymentLedgerReportMapper;
import vip.fairy.commerceflow.payment.infrastructure.persistence.PaymentLedgerReportMapper.DayWindow;

@Service
public class PaymentLedgerReportService {

  private static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";
  private static final int MAX_DAYS = 366;
  private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(2);

  private final PaymentLedgerReportMapper ledger;

  public PaymentLedgerReportService(PaymentLedgerReportMapper ledger) {
    this.ledger = ledger;
  }

  public Report summarize(Query query) {
    if (query == null || query.startDate() == null || query.endDate() == null
        || query.endDate().isBefore(query.startDate()) || query.endDate().equals(LocalDate.MAX)
        || ChronoUnit.DAYS.between(query.startDate(), query.endDate()) >= MAX_DAYS) {
      throw new IllegalArgumentException("Specify an inclusive date range of 1 to 366 days");
    }
    if (query.currency() != null && !query.currency().matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("Currency must be a three-letter uppercase code");
    }
    List<Long> merchantIds = query.merchantIds();
    if (merchantIds != null && (merchantIds.isEmpty()
        || merchantIds.stream().anyMatch(id -> id == null || id <= 0))) {
      throw new IllegalArgumentException("Supply a nonempty list of valid merchant identifiers");
    }
    ZoneId zone = reportZone(query.timeZone());
    List<DayWindow> days = query.startDate().datesUntil(query.endDate().plusDays(1))
      .map(date -> new DayWindow(date, date.atStartOfDay(zone).toEpochSecond(),
        date.plusDays(1).atStartOfDay(zone).toEpochSecond()))
      .toList();
    var daily = new LinkedHashMap<DailyKey, Accumulator>();
    var byChannel = new LinkedHashMap<ChannelKey, Accumulator>();
    var totals = new TreeMap<String, Accumulator>();
    var unattributed = new TreeMap<String, Accumulator>();
    if (query.currency() != null) {
      totals.put(query.currency(), new Accumulator());
    }
    String channel = query.channel() == null ? null : query.channel().name();
    for (var row : ledger.summarize(days, days.getFirst().startSecond(), days.getLast().endSecond(),
        channel, query.currency(), merchantIds)) {
      daily.computeIfAbsent(new DailyKey(row.reportDate(), row.channel(), row.currency()), key -> new Accumulator())
        .add(row);
      byChannel.computeIfAbsent(new ChannelKey(row.channel(), row.currency()), key -> new Accumulator()).add(row);
      totals.computeIfAbsent(row.currency(), key -> new Accumulator()).add(row);
      if (row.unattributed()) {
        unattributed.computeIfAbsent(row.currency(), key -> new Accumulator()).add(row);
      }
    }
    return new Report(query.startDate(), query.endDate(), zone.getId(), query.channel(), query.currency(),
      daily.entrySet().stream().map(entry -> new DailySummary(entry.getKey().date(),
        entry.getKey().channel(), entry.getKey().currency(), entry.getValue().summary())).toList(),
      byChannel.entrySet().stream().map(entry -> new ChannelSummary(entry.getKey().channel(),
        entry.getKey().currency(), entry.getValue().summary())).toList(),
      currencySummaries(totals), currencySummaries(unattributed));
  }

  private List<CurrencySummary> currencySummaries(Map<String, Accumulator> summaries) {
    return summaries.entrySet().stream()
      .map(entry -> new CurrencySummary(entry.getKey(), entry.getValue().summary())).toList();
  }

  private ZoneId reportZone(String timeZone) {
    if (timeZone == null) {
      return ZoneId.of(DEFAULT_TIME_ZONE);
    }
    if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
      throw new IllegalArgumentException("Specify a named time zone");
    }
    return ZoneId.of(timeZone);
  }

  public record Query(LocalDate startDate, LocalDate endDate, String timeZone, PaymentChannelV1 channel,
                      String currency, List<Long> merchantIds) {
  }

  public record Report(LocalDate startDate, LocalDate endDate, String timeZone, PaymentChannelV1 channel,
                       String currency, List<DailySummary> daily, List<ChannelSummary> channels,
                       List<CurrencySummary> totals, List<CurrencySummary> unattributed) {
  }

  public record DailySummary(LocalDate date, String channel, String currency, Summary summary) {
  }

  public record ChannelSummary(String channel, String currency, Summary summary) {
  }

  public record CurrencySummary(String currency, Summary summary) {
  }

  public record Summary(long createdCount, long paidCount, String receivedAmount, long refundCount,
                        String refundedAmount, String netAmount, Map<String, Long> currentPaymentStatuses,
                        long missingPaidAtCount, long missingRefundSucceededAtCount) {
  }

  private record DailyKey(LocalDate date, String channel, String currency) {
  }

  private record ChannelKey(String channel, String currency) {
  }

  private static final class Accumulator {
    private long createdCount;
    private long paidCount;
    private long refundCount;
    private long missingPaidAtCount;
    private long missingRefundSucceededAtCount;
    private BigDecimal received = ZERO_AMOUNT;
    private BigDecimal refunded = ZERO_AMOUNT;
    private final Map<String, Long> currentPaymentStatuses = new TreeMap<>();

    void add(PaymentLedgerReportMapper.Aggregate row) {
      createdCount += row.createdCount();
      paidCount += row.paidCount();
      refundCount += row.refundCount();
      missingPaidAtCount += row.missingPaidAtCount();
      missingRefundSucceededAtCount += row.missingRefundSucceededAtCount();
      received = received.add(row.receivedAmount());
      refunded = refunded.add(row.refundedAmount());
      if (row.paymentStatus() != null) {
        currentPaymentStatuses.merge(row.paymentStatus(), row.createdCount(), Long::sum);
      }
    }

    Summary summary() {
      return new Summary(createdCount, paidCount, received.toPlainString(), refundCount, refunded.toPlainString(),
        received.subtract(refunded).toPlainString(),
        Map.copyOf(currentPaymentStatuses), missingPaidAtCount, missingRefundSucceededAtCount);
    }
  }
}
