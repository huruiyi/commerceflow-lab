package vip.fairy.commerceflow.commerce.fulfillment.application;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.MerchantFulfillmentRecovery;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.MerchantFulfillmentJobMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
@ConditionalOnProperty(name = "commerceflow.fulfillment.remote.enabled", havingValue = "false", matchIfMissing = true)
public class MerchantFulfillmentRecoveryService {

  private final MerchantFulfillmentJobMapper jobs;

  public MerchantFulfillmentRecoveryService(MerchantFulfillmentJobMapper jobs) {
    this.jobs = jobs;
  }

  public List<MerchantFulfillmentJob> reviewable(int limit) {
    return jobs.reviewable(Math.clamp(limit, 1, 100));
  }

  public Review detail(String commandId) {
    MerchantFulfillmentJob job = require(jobs.find(commandId));
    return new Review(job, jobs.recoveryHistory(commandId));
  }

  @Transactional
  public MerchantFulfillmentRecovery retry(String commandId, String recoveryId, Long operatorId,
                                          String reason, String evidence) {
    MerchantFulfillmentJob job = require(jobs.lockForRecovery(commandId));
    String normalizedReason = reason.trim();
    String normalizedEvidence = evidence.trim();
    MerchantFulfillmentRecovery prior = jobs.findRecovery(commandId, recoveryId);
    if (prior != null) {
      if (!prior.operatorId().equals(operatorId) || !prior.reason().equals(normalizedReason)
          || !prior.evidence().equals(normalizedEvidence)) {
        throw new BusinessRuleException("Recovery ID was reused with different details");
      }
      return prior;
    }
    if (jobs.requeueReview(commandId) != 1) {
      throw new BusinessRuleException("Fulfillment operation is not waiting for manual reconciliation");
    }
    jobs.insertRecovery(new MerchantFulfillmentRecovery(commandId, recoveryId, operatorId, job.step(),
      job.lastError(), normalizedReason, normalizedEvidence, null));
    return jobs.findRecovery(commandId, recoveryId);
  }

  private MerchantFulfillmentJob require(MerchantFulfillmentJob job) {
    if (job == null) throw new ResourceNotFoundException("Fulfillment operation not found");
    return job;
  }

  public record Review(MerchantFulfillmentJob operation, List<MerchantFulfillmentRecovery> recoveries) {
  }
}
