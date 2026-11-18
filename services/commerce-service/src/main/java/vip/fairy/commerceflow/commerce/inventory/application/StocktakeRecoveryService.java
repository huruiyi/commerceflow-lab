package vip.fairy.commerceflow.commerce.inventory.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryOrderV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.StocktakeV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.DeliveryRecoveryView;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.StocktakeReviewMapper;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.StocktakeReviewMapper.Row;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.DeliveredInventoryRemoteClient;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class StocktakeRecoveryService {

  private final StocktakeReviewMapper reviews;
  private final InventoryCommandDeliveryMapper deliveries;
  private final InventoryCommandPort commands;
  private final DeliveredInventoryOrderV1 orders;
  private final DeliveredInventoryRemoteClient inventory;
  private final ObjectMapper json;

  public StocktakeRecoveryService(StocktakeReviewMapper reviews, InventoryCommandDeliveryMapper deliveries,
                                  InventoryCommandPort commands, DeliveredInventoryOrderV1 orders,
                                  DeliveredInventoryRemoteClient inventory, ObjectMapper json) {
    this.reviews = reviews;
    this.deliveries = deliveries;
    this.commands = commands;
    this.orders = orders;
    this.inventory = inventory;
    this.json = json;
  }

  public StocktakeV1.Baseline baseline(long productId) {
    return inventory.stocktakeBaseline(productId);
  }

  @Transactional
  public Review submit(Proposal proposal, String actor) {
    validate(proposal);
    String encoded = json.writeValueAsString(proposal);
    if (reviews.create(proposal.reference(), encoded, actor) == 1) {
      requireEligible(proposal, false);
    }
    Row row = require(reviews.lock(proposal.reference()));
    if (!row.countedBy().equals(actor) || !proposal.equals(decode(row))) {
      throw new BusinessRuleException("Stocktake reference already exists with different evidence");
    }
    return view(row);
  }

  public Review get(String reference) {
    return view(require(reviews.find(reference)));
  }

  @Transactional
  public void approve(String reference, String actor) {
    Row row = require(reviews.lock(reference));
    if (actor.equalsIgnoreCase(row.countedBy())) {
      throw new BusinessRuleException("The person who recorded the stocktake cannot approve it");
    }
    if (row.approvedBy() == null) {
      reviews.approve(reference, actor);
    } else if (!actor.equals(row.approvedBy())) {
      throw new BusinessRuleException("Stocktake approval was already recorded by a different reviewer");
    }
  }

  @Transactional
  public StocktakeV1.Receipt applyApproved(String reference) {
    commands.requireWritable();
    Row row = require(reviews.lock(reference));
    if (row.receipt() != null) {
      return json.readValue(row.receipt(), StocktakeV1.Receipt.class);
    }
    if (row.approvedBy() == null) {
      throw new BusinessRuleException("Independent stocktake approval is required");
    }
    Proposal proposal = decode(row);
    List<DeliveryRecoveryView> affected = requireEligible(proposal, true);
    List<Long> orderIds = affected.stream().map(DeliveryRecoveryView::orderId).distinct().sorted().toList();
    if (orderIds.size() != affected.size()) {
      throw new BusinessRuleException("Each order must be listed once");
    }
    StocktakeV1.Receipt receipt = inventory.applyStocktake(new StocktakeV1(reference, proposal.productId(),
      proposal.baseline(), proposal.countedAvailable(), proposal.countedAt(), proposal.note(),
      row.countedBy(), row.approvedBy(), orderIds));
    for (DeliveryRecoveryView delivery : affected) {
      orders.markReconciled(delivery.orderId());
      if (deliveries.closeReconciled(delivery.commandId(), row.approvedBy(),
          "Stocktake " + reference + "; no order-level outbound attribution") != 1) {
        throw new BusinessRuleException("Inventory delivery changed during stocktake reconciliation");
      }
    }
    reviews.complete(reference, json.writeValueAsString(receipt));
    return receipt;
  }

  private List<DeliveryRecoveryView> requireEligible(Proposal proposal, boolean lock) {
    return proposal.commandIds().stream().sorted().map(commandId -> {
      var delivery = lock ? deliveries.lockRecovery(commandId) : deliveries.byCommandId(commandId);
      if (delivery == null || !"RESERVE".equals(delivery.commandType())
          || !"RECONCILIATION_REQUIRED".equals(delivery.status())) {
        throw new BusinessRuleException("Stocktake requires outstanding delivered reservation cases");
      }
      var order = orders.requireDelivered(delivery.orderId());
      Map<Long, Integer> quantities = order.quantities();
      if (quantities.size() != 1 || !quantities.containsKey(proposal.productId())
          || proposal.countedAt().isBefore(order.deliveredAt())) {
        throw new BusinessRuleException("Every order must be for the counted product and delivered before the count");
      }
      return delivery;
    }).toList();
  }

  private void validate(Proposal proposal) {
    if (proposal == null || proposal.reference() == null || !proposal.reference().matches("[A-Za-z0-9_-]{1,80}")
        || proposal.productId() <= 0 || proposal.baseline() == null
        || proposal.baseline().productId() != proposal.productId() || proposal.baseline().available() < 0
        || proposal.countedAvailable() == null || proposal.countedAvailable() < 0
        || proposal.countedAt() == null || proposal.countedAt().isAfter(Instant.now())
        || proposal.note() == null || proposal.note().isBlank() || proposal.note().length() > 300
        || proposal.commandIds() == null || proposal.commandIds().isEmpty() || proposal.commandIds().size() > 100
        || proposal.commandIds().stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 100)
        || proposal.commandIds().stream().distinct().count() != proposal.commandIds().size()) {
      throw new IllegalArgumentException("Stocktake requires a count, a baseline, a report reference and unique cases");
    }
  }

  private Row require(Row row) {
    if (row == null) {
      throw new ResourceNotFoundException("Stocktake review was not found");
    }
    return row;
  }

  private Proposal decode(Row row) {
    return json.readValue(row.proposal(), Proposal.class);
  }

  private Review view(Row row) {
    var receipt = row.receipt() == null ? null : json.readValue(row.receipt(), StocktakeV1.Receipt.class);
    return new Review(decode(row), row.countedBy(), row.approvedBy(), receipt);
  }

  public record Proposal(String reference, long productId, StocktakeV1.Baseline baseline, Integer countedAvailable,
                         Instant countedAt, String note, List<String> commandIds) {
  }

  public record Review(Proposal proposal, String countedBy, String approvedBy, StocktakeV1.Receipt receipt) {
  }
}
