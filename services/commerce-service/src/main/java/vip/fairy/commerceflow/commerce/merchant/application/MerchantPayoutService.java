package vip.fairy.commerceflow.commerce.merchant.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.merchant.domain.MerchantPayout;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.MerchantPayoutMapper;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.PayoutChannelClient;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.PayoutChannelClient.Outcome;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.SettlementMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class MerchantPayoutService {

  private static final Logger log = LoggerFactory.getLogger(MerchantPayoutService.class);
  private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED");

  private final SettlementMapper ledger;
  private final MerchantPayoutMapper payouts;
  private final PayoutChannelClient channel;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private final String callbackSecret;

  public MerchantPayoutService(SettlementMapper ledger, MerchantPayoutMapper payouts, PayoutChannelClient channel,
                                TransactionTemplate transactions, ObjectMapper mapper,
                                @Value("${PAYOUT_CALLBACK_SECRET:demo-payout-callback-secret}") String callbackSecret) {
    this.ledger = ledger;
    this.payouts = payouts;
    this.channel = channel;
    this.transactions = transactions;
    this.mapper = mapper;
    this.callbackSecret = callbackSecret;
  }

  @Transactional
  public MerchantPayout request(Long merchant, String statement, String attempt, Long admin, boolean fail,
                                String recoveryOf, String reason) {
    if (ledger.lockAccount(merchant) == null) throw new ResourceNotFoundException("Settlement account not found");
    var value = ledger.statement(statement);
    if (value == null || !merchant.equals(value.merchantId())) {
      throw new ResourceNotFoundException("Statement not found");
    }
    String recoveryReason = reason == null || reason.isBlank() ? null : reason.strip();
    if ((recoveryOf == null) != (recoveryReason == null)) {
      throw new IllegalArgumentException("Payout recovery requires the original failed attempt and a reason");
    }
    var replay = payouts.find(attempt);
    if (replay != null) {
      if (!statement.equals(replay.statementId()) || !merchant.equals(replay.merchantId())
        || fail != replay.simulateFailure() || !admin.equals(replay.requestedBy())
        || !Objects.equals(recoveryOf, replay.recoveryOf())
        || !Objects.equals(recoveryReason, replay.recoveryReason())) {
        throw new BusinessRuleException("Payout request conflicts with its original statement or parameters");
      }
      return replay;
    }
    var latest = payouts.attempts(statement).stream().findFirst().orElse(null);
    if (recoveryOf != null && (latest == null || !recoveryOf.equals(latest.id())
        || !"FAILED".equals(latest.status()) || latest.channelPayoutNo() == null)) {
      throw new BusinessRuleException("Only the latest payout with a confirmed failure can be recovered");
    }
    if (recoveryOf == null && latest != null) {
      if ("FAILED".equals(latest.status())) {
        throw new BusinessRuleException("Record recovery evidence for the failed payout");
      }
      return latest;
    }
    payouts.insert(new MerchantPayout(attempt, statement, merchant, value.payable(), "CNY", "REQUESTED",
      fail, admin, null, null, null, recoveryOf, recoveryReason));
    return payouts.find(attempt);
  }

  public List<MerchantPayout> attempts(Long merchant, String statement) {
    var value = ledger.statement(statement);
    if (value == null || !merchant.equals(value.merchantId())) {
      throw new ResourceNotFoundException("Statement not found");
    }
    return payouts.attempts(statement);
  }

  @Scheduled(fixedDelayString = "${commerceflow.merchant.payout-scan:2s}")
  @SchedulerLock(name = "merchantPayoutReconciliation", lockAtLeastFor = "PT1S", lockAtMostFor = "PT2M")
  public void reconcile() {
    for (var attempt : payouts.due()) {
      try {
        var outcome = channel.find(attempt.id()).orElseGet(() -> channel.create(attempt));
        transactions.executeWithoutResult(status -> observe(outcome));
      } catch (RuntimeException exception) {
        payouts.defer(attempt.id());
        log.warn("Payout {} remains awaiting channel confirmation", attempt.id());
      }
    }
  }

  @Transactional
  public void callback(String raw, String signature) {
    if (!authorized(raw, signature)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid payout callback signature");
    }
    var outcome = mapper.readValue(raw, Outcome.class);
    if (!TERMINAL.contains(outcome.status())) throw new IllegalArgumentException("Terminal payout outcome is required");
    observe(outcome);
  }

  private boolean authorized(String raw, String signature) {
    try {
      return signature != null && MessageDigest.isEqual(PayoutChannelClient.hmac(callbackSecret, raw),
        Base64.getDecoder().decode(signature.getBytes(StandardCharsets.UTF_8)));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private void observe(Outcome outcome) {
    if (outcome == null || outcome.attemptId() == null) throw new IllegalArgumentException("Missing payout attempt");
    var existing = payouts.lock(outcome.attemptId());
    if (existing == null) throw new ResourceNotFoundException("Payout attempt not found");
    if (!existing.statementId().equals(outcome.statementId()) || !existing.merchantId().equals(outcome.beneficiaryId())
      || !existing.currency().equals(outcome.currency()) || outcome.amount() == null
      || existing.amount().compareTo(outcome.amount()) != 0 || outcome.channelPayoutNo() == null
      || existing.simulateFailure() != outcome.simulateFailure()
      || (existing.channelPayoutNo() != null && !existing.channelPayoutNo().equals(outcome.channelPayoutNo()))) {
      throw new BusinessRuleException("Channel payout does not match its frozen statement");
    }
    if (TERMINAL.contains(existing.status())) {
      if (!existing.status().equals(outcome.status()) && !"PENDING".equals(outcome.status())) {
        throw new BusinessRuleException("Channel payout terminal result conflicts with its receipt");
      }
      return;
    }
    if (!"PENDING".equals(outcome.status()) && (!TERMINAL.contains(outcome.status()) || outcome.resolvedAt() == null)) {
      throw new BusinessRuleException("Channel payout result is incomplete");
    }
    payouts.observe(existing.id(), outcome.status(), outcome.channelPayoutNo(), outcome.resolvedAt());
  }
}
