package vip.fairy.commerceflow.commerce.fulfillment.application;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentJob;
import vip.fairy.commerceflow.commerce.fulfillment.domain.ShipmentRecovery;
import vip.fairy.commerceflow.commerce.fulfillment.infrastructure.ShipmentJobMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
@ConditionalOnProperty(name = "commerceflow.fulfillment.remote.enabled", havingValue = "false", matchIfMissing = true)
public class ShipmentRecoveryService {

  private final ShipmentJobMapper jobs;

  public ShipmentRecoveryService(ShipmentJobMapper jobs) {
    this.jobs = jobs;
  }

  public List<ShipmentJob> reviewable(int limit) {
    return jobs.reviewable(Math.clamp(limit, 1, 100));
  }

  public ShipmentReview detail(Long orderId) {
    ShipmentJob job = require(jobs.find(orderId));
    return new ShipmentReview(job, jobs.recoveryHistory(orderId));
  }

  @Transactional
  public ShipmentRecovery retry(Long orderId, String recoveryId, String commandId, Long operatorId,
                                String reason, String evidence) {
    ShipmentJob job = require(jobs.lockForRecovery(commandId));
    if (!job.orderId().equals(orderId)) {
      throw new BusinessRuleException("Recovery does not match the shipment operation");
    }
    String normalizedReason = reason.trim();
    String normalizedEvidence = evidence.trim();
    ShipmentRecovery prior = jobs.findRecovery(orderId, recoveryId);
    if (prior != null) {
      if (!prior.commandId().equals(commandId) || !prior.operatorId().equals(operatorId)
          || !prior.reason().equals(normalizedReason)
          || !prior.evidence().equals(normalizedEvidence)) {
        throw new BusinessRuleException("Recovery ID was reused with different details");
      }
      return prior;
    }
    if (jobs.requeueReview(commandId) != 1) {
      throw new BusinessRuleException("Shipment is not waiting for manual reconciliation");
    }
    jobs.insertRecovery(new ShipmentRecovery(orderId, recoveryId, commandId, operatorId, job.step(),
      job.lastError(), normalizedReason, normalizedEvidence, null));
    return jobs.findRecovery(orderId, recoveryId);
  }

  private ShipmentJob require(ShipmentJob job) {
    if (job == null) throw new ResourceNotFoundException("Shipment operation not found");
    return job;
  }

  public record ShipmentReview(ShipmentJob operation, List<ShipmentRecovery> recoveries) {
  }
}
