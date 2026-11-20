package vip.fairy.commerceflow.commerce.refund.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.refund.domain.LineAfterSale;
import vip.fairy.commerceflow.commerce.refund.domain.ReturnWorkOrder;
import vip.fairy.commerceflow.commerce.refund.domain.ReturnWorkOrder.Action;
import vip.fairy.commerceflow.commerce.refund.infrastructure.LineAfterSaleMapper;
import vip.fairy.commerceflow.commerce.refund.infrastructure.ReturnWorkOrderMapper;
import vip.fairy.commerceflow.commerce.refund.infrastructure.WarehouseReturnClient;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class ReturnWorkOrderService {

  private final ReturnWorkOrderMapper work;
  private final LineAfterSaleMapper cases;
  private final LineAfterSaleService sale;
  private final WarehouseReturnClient warehouse;

  ReturnWorkOrderService(ReturnWorkOrderMapper work, LineAfterSaleMapper cases, LineAfterSaleService sale,
                         WarehouseReturnClient warehouse) {
    this.work = work;
    this.cases = cases;
    this.sale = sale;
    this.warehouse = warehouse;
  }

  @Scheduled(fixedDelayString = "${commerceflow.after-sales.dispatch-delay:2s}")
  public void reconcile() {
    work.sync();
  }

  public List<ReturnWorkOrder> list(long merchantId) {
    return work.forMerchant(merchantId);
  }

  public Detail detail(long merchantId, String id) {
    var ticket = work.find(id, merchantId);
    if (ticket == null) throw notFound();
    return new Detail(ticket, work.actions(id));
  }

  public Evidence evidence(long merchantId, String id) {
    detail(merchantId, id);
    var value = cases.find(id);
    if ("REJECTED".equals(value.warehouseReturnStatus())) return null;
    return evidence(value);
  }

  @Transactional
  public Detail decide(long merchantId, String id, String requestId, String action, String note, Integer quantity) {
    var value = cases.lock(id);
    if (value == null || !value.merchantId().equals(merchantId)) throw notFound();
    var ticket = work.find(id, merchantId);
    if (ticket == null) throw notFound();
    var prior = work.action(requestId);
    if (prior != null) {
      if (prior.caseId().equals(id) && prior.actorId() == merchantId && prior.action().equals(action)
          && prior.note().equals(note) && Objects.equals(prior.quantity(), quantity)) return detail(merchantId, id);
      throw conflict();
    }
    if (!"REVIEW_REQUIRED".equals(value.status()) || value.refundNumber() != null) throw conflict();
    String evidenceId = null;
    String phase;
    if ("APPROVE".equals(action)) {
      var confirmed = evidence(value);
      if (quantity == null || quantity < 1 || confirmed == null || quantity > confirmed.qualifiedQuantity()) {
        throw new BusinessRuleException("Approval must use the qualified quantity in the warehouse supplement");
      }
      var amount = sale.repriceException(value, quantity, cases.lockForOrder(value.orderId()));
      if (cases.approveException(id, quantity, amount, note) != 1) throw conflict();
      evidenceId = confirmed.id();
      phase = "APPROVED";
    } else {
      if (quantity != null) throw conflict();
      phase = switch (action) {
        case "REQUEST_EVIDENCE" -> "WAITING_EVIDENCE";
        case "REJECT" -> "REJECTED";
        default -> throw conflict();
      };
      String next = "REJECTED".equals(phase) ? "REJECTED" : "REVIEW_REQUIRED";
      if (cases.noteException(id, next, note) != 1) throw conflict();
    }
    if (work.advance(id, phase) != 1) throw conflict();
    try {
      work.record(new Action(requestId, id, merchantId, action, note, quantity, evidenceId, null));
    } catch (DuplicateKeyException collision) {
      throw conflict();
    }
    return detail(merchantId, id);
  }

  private Evidence evidence(LineAfterSale value) {
    if (!"SHORT_RECEIVED".equals(value.warehouseReturnStatus()) && !"FAILED".equals(value.warehouseReturnStatus())) {
      return null;
    }
    var receipt = warehouse.submit(new WarehouseReturnClient.Plan(value.id(), value.outboundId(), value.orderId(),
      value.orderLineId(), value.warehouseId(), value.trackingNumber(), value.quantity()));
    if (!receipt.status().equals(value.warehouseReturnStatus())
        || !Objects.equals(receipt.receivedQuantity(), value.receivedQuantity())) {
      throw new BusinessRuleException("Warehouse supplement does not match this return");
    }
    if (receipt.supplementId() == null) return null;
    return new Evidence(receipt.supplementId(), receipt.qualifiedQuantity(),
      receipt.supplementNote(), receipt.supplementAt());
  }

  private ResourceNotFoundException notFound() {
    return new ResourceNotFoundException("Return work order not found");
  }

  private BusinessRuleException conflict() {
    return new BusinessRuleException("Return work order has changed or does not allow this action");
  }

  public record Detail(ReturnWorkOrder ticket, List<Action> actions) {
  }

  public record Evidence(String id, int qualifiedQuantity, String note, Instant recordedAt) {
  }
}
