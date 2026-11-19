package vip.fairy.commerceflow.simulator.payment.service;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.simulator.payment.domain.ChannelPayout;
import vip.fairy.commerceflow.simulator.payment.mapper.ChannelPayoutMapper;

@Service
public class ChannelPayoutService {

  private final ChannelPayoutMapper payouts;

  public ChannelPayoutService(ChannelPayoutMapper payouts) {
    this.payouts = payouts;
  }

  @Transactional
  public ChannelPayout create(String id, String statement, Long merchant, BigDecimal amount, boolean fail) {
    try {
      payouts.create(new ChannelPayout(id, statement, merchant, amount, "CNY",
        "PYT-" + id.replace("-", ""), "PENDING", fail, null, null, null));
    } catch (DuplicateKeyException exception) {
      var existing = payouts.lock(id);
      if (existing == null || !existing.statementId().equals(statement) || !existing.beneficiaryId().equals(merchant)
        || existing.amount().compareTo(amount) != 0 || existing.simulateFailure() != fail) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Payout request conflicts with an active attempt");
      }
      return existing;
    }
    return get(id);
  }

  public ChannelPayout get(String id) {
    var value = payouts.find(id);
    if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payout not found");
    return value;
  }

  public List<ChannelPayout> recent() {
    return payouts.recent();
  }
}
