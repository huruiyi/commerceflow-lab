package vip.fairy.commerceflow.commerce.refund.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryLineCreditV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryLineCreditV1.ExchangeAction;
import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
import vip.fairy.commerceflow.commerce.logistics.application.ReturnShipmentService;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderLineRefundV1;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderRefundPort;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentWriteAdmissionV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundCreateCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundQueryV1;
import vip.fairy.commerceflow.commerce.refund.infrastructure.LineAfterSaleMapper;
import vip.fairy.commerceflow.commerce.refund.domain.LineAfterSale;
import vip.fairy.commerceflow.commerce.refund.infrastructure.WarehouseReturnClient;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class LineAfterSaleService {

  private static final Set<String> TERMINAL = Set.of("FAILED", "REJECTED", "COMPLETED");
  private static final Set<String> UNSHIPPED = Set.of("PAID", "CONFIRMED");
  private static final Set<String> DELIVERED = Set.of("RECEIVED", "COMPLETED");
  private static final Set<String> EXCHANGE_ACCEPTED = Set.of("PREPARING", "EXCHANGE_OUT_OF_STOCK",
    "EXCHANGE_READY", "EXCHANGE_SHIPPING", "COMPLETED");
  private final LineAfterSaleMapper cases;
  private final OrderRefundPort orders;
  private final OrderLineRefundV1 admission;
  private final PaymentOrchestrationV1 payments;
  private final PaymentWriteAdmissionV1 paymentWrites;
  private final InventoryLineCreditV1 stock;
  private final ReturnShipmentService returns;
  private final WarehouseReturnClient warehouse;

  public LineAfterSaleService(LineAfterSaleMapper cases, OrderRefundPort orders,
                              OrderLineRefundV1 admission, PaymentOrchestrationV1 payments,
                              PaymentWriteAdmissionV1 paymentWrites, InventoryLineCreditV1 stock,
                              ReturnShipmentService returns, WarehouseReturnClient warehouse) {
    this.cases = cases;
    this.orders = orders;
    this.admission = admission;
    this.payments = payments;
    this.paymentWrites = paymentWrites;
    this.stock = stock;
    this.returns = returns;
    this.warehouse = warehouse;
  }

  @Transactional
  public Offer quote(Long customerId, Long orderId, Long lineId, int quantity, Long warehouseId, String outboundId,
                     boolean exchange) {
    return offer(customerId, orderId, lineId, quantity, cases.forOrder(orderId), warehouseId, outboundId, exchange);
  }

  @Transactional
  public LineAfterSale submit(Long customerId, Long orderId, Long lineId, int quantity, UUID requestId, String reason,
                              Long warehouseId, String outboundId, boolean exchange) {
    cases.serializeApplication(orderId);
    List<LineAfterSale> existing = cases.forOrder(orderId);
    LineAfterSale prior = cases.find(requestId.toString());
    if (prior != null) {
      if (customerId.equals(prior.customerId()) && orderId.equals(prior.orderId())
          && lineId.equals(prior.orderLineId()) && prior.quantity() == quantity && prior.reason().equals(reason)
          && exchange == exchange(prior)
          && (Objects.equals(warehouseId, prior.warehouseId()) || (warehouseId == null && !beforeShipment(prior)))
          && (Objects.equals(outboundId, prior.outboundId()) || (outboundId == null && !beforeShipment(prior)))) {
        return prior;
      }
      throw new BusinessRuleException("After-sales request identifier is already in use");
    }
    Offer offer = offer(customerId, orderId, lineId, quantity, existing, warehouseId, outboundId, exchange);
    LineAfterSale value = new LineAfterSale(requestId.toString(), orderId, lineId, offer.productId(), customerId,
      offer.merchantId(), quantity, offer.merchandiseAmount(), offer.shippingAmount(), offer.currency(),
      offer.kind(), "APPLIED", reason, null, null, null, null, null, null,
      offer.warehouseId(), offer.outboundId(), null, null, null, null);
    try {
      cases.insert(value);
    } catch (DuplicateKeyException conflict) {
      throw new BusinessRuleException("An after-sales request already exists");
    }
    return value;
  }

  private Offer offer(Long customerId, Long orderId, Long lineId, int quantity, List<LineAfterSale> existing,
                      Long warehouseId, String outboundId, boolean exchange) {
    var order = orders.find(customerId, orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    var lines = orders.items(orderId);
    var line = lines.stream().filter(item -> lineId.equals(item.getId())).findFirst()
      .orElseThrow(() -> new ResourceNotFoundException("Order line not found"));
    boolean unshipped = UNSHIPPED.contains(order.getStatus());
    if ((!unshipped && !DELIVERED.contains(order.getStatus())) || !"COMMITTED".equals(order.getInventoryStatus())) {
      throw new BusinessRuleException("Order is not eligible for line after-sales");
    }
    if (exchange && unshipped) {
      throw new BusinessRuleException("Exchange requires a received order");
    }
    if (existing.stream().anyMatch(value -> !TERMINAL.contains(value.status()))) {
      throw new BusinessRuleException("Finish the active after-sales case before applying again");
    }
    List<LineAfterSale> completed = existing.stream().filter(value -> "COMPLETED".equals(value.status())).toList();
    var plan = orders.warehousePlan(orderId);
    boolean warehouseOrder = plan.amounts().stream().anyMatch(amount -> amount.warehouseId() != 1);
    if (unshipped && ((warehouseOrder && warehouseId == null) || outboundId != null)) {
      throw new BusinessRuleException("Choose an allocated warehouse for the unshipped refund");
    }
    var source = unshipped ? null
      : requireOrigin(orderId, lineId, line.getMerchantId(), line.getProductId(), warehouseId, outboundId);
    Long resolvedWarehouse = source == null ? warehouseId : Long.valueOf(source.warehouseId());
    int assigned = line.getQuantity();
    BigDecimal net = line.getSubtotal().subtract(line.getDiscountAmount());
    if (resolvedWarehouse != null && (!plan.amounts().isEmpty() || resolvedWarehouse != 1)) {
      var amount = plan.amounts().stream()
        .filter(value -> value.orderLineId() == lineId && value.warehouseId() == resolvedWarehouse)
        .findFirst().orElseThrow(() -> new BusinessRuleException("Order line is not allocated to the warehouse"));
      assigned = amount.quantity();
      net = amount.grossAmount().subtract(amount.discountAmount());
    }
    var previousCases = completed.stream().filter(value -> lineId.equals(value.orderLineId()))
      .filter(value -> resolvedWarehouse == null
        || resolvedWarehouse.equals(value.warehouseId() == null ? 1L : value.warehouseId())).toList();
    int previous = previousCases.stream().mapToInt(LineAfterSale::executableQuantity).sum();
    int remaining = assigned - previous;
    if (unshipped) {
      int sent = plan.shipments().stream().filter(ship -> ship.orderLineId() == lineId
        && (warehouseId == null || ship.warehouseId() == warehouseId)).mapToInt(ship -> ship.quantity()).sum();
      remaining -= sent;
    } else {
      int returned = previousCases.stream().filter(value -> !beforeShipment(value)
          && (value.outboundId() == null || source.outboundId().equals(value.outboundId())))
        .mapToInt(LineAfterSale::executableQuantity).sum();
      remaining = Math.min(remaining, source.quantity() - returned);
    }
    if (quantity < 1 || quantity > remaining) {
      throw new BusinessRuleException("Requested quantity exceeds the remaining eligible order line quantity");
    }
    if (exchange) {
      return new Offer(line.getProductId(), line.getMerchantId(), remaining, "EXCHANGE", BigDecimal.ZERO.setScale(2),
        BigDecimal.ZERO.setScale(2), order.getCurrency(), resolvedWarehouse, source.outboundId());
    }
    BigDecimal amount = LineRefundAmount.calculate(net, assigned, previous, quantity);
    int purchased = lines.stream().mapToInt(item -> item.getQuantity()).sum();
    boolean finalUnshipped = unshipped
      && completed.stream().mapToInt(LineAfterSale::executableQuantity).sum() + quantity == purchased;
    BigDecimal freight = finalUnshipped && completed.stream().noneMatch(value -> value.shippingAmount().signum() > 0)
      ? order.getShippingFee() : BigDecimal.ZERO.setScale(2);
    return new Offer(line.getProductId(), line.getMerchantId(), remaining,
      unshipped ? "BEFORE_SHIPMENT" : "RETURN", amount, freight, order.getCurrency(),
      resolvedWarehouse, source == null ? null : source.outboundId());
  }

  BigDecimal repriceException(LineAfterSale value, int quantity, List<LineAfterSale> existing) {
    var related = existing.stream().filter(item -> !item.id().equals(value.id())).toList();
    return offer(value.customerId(), value.orderId(), value.orderLineId(), quantity, related,
      value.warehouseId(), value.outboundId(), false).merchandiseAmount();
  }

  public List<LineAfterSale> customerCases(Long customerId, Long orderId) {
    orders.find(customerId, orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    return cases.forCustomer(customerId, orderId);
  }

  public List<LineAfterSale> merchantCases(Long merchantId, Long orderId) {
    if (orders.items(orderId).stream().noneMatch(line -> merchantId.equals(line.getMerchantId()))) {
      throw new ResourceNotFoundException("Merchant order not found");
    }
    return cases.forMerchant(merchantId, orderId);
  }

  @Transactional
  public LineAfterSale sendReturn(Long customerId, String id, String carrier, String tracking,
                                 BigDecimal returnShippingCost) {
    LineAfterSale value = cases.lock(id);
    if (value == null || !customerId.equals(value.customerId())) {
      throw new ResourceNotFoundException("After-sales case not found");
    }
    String trackingNumber = tracking.strip();
    if (returnShippingCost != null) returnShippingCost = returnShippingCost.setScale(2);
    if (Objects.equals(returnShippingCost, value.returnShippingCost())
        && returns.matches(id, carrier, trackingNumber)) return value;
    requireTransition(cases.customerReturn(id, customerId, trackingNumber, returnShippingCost));
    var order = orders.find(customerId, value.orderId())
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    returns.bind(id, value.orderId(), order.getOrderNumber(), carrier, trackingNumber);
    return require(id);
  }

  public LogisticsResponse customerReturnTracking(Long customerId, String id) {
    if (!customerId.equals(require(id).customerId())) throw new ResourceNotFoundException("After-sales case not found");
    return returns.tracking(id);
  }

  public LogisticsResponse merchantReturnTracking(Long merchantId, String id) {
    owned(merchantId, id);
    return returns.tracking(id);
  }

  @Transactional
  public LineAfterSale review(Long merchantId, String id, boolean approved, String note) {
    LineAfterSale value = merchantCase(merchantId, cases.lock(id));
    String target = !approved ? "REJECTED" : beforeShipment(value) ? "PREPARING" : "WAITING_RETURN";
    if (target.equals(value.status()) && note.equals(value.decision())) return value;
    if (approved && exchange(value) && value.trackingNumber() != null && !"REJECTED".equals(value.status())) {
      return value;
    }
    requireTransition(cases.merchantDecision(id, merchantId, "APPLIED", target, note, null));
    return require(id);
  }

  @Transactional
  public LineAfterSale inspect(Long merchantId, String id, boolean accepted, String note) {
    LineAfterSale value = merchantCase(merchantId, cases.lock(id));
    String target = accepted ? "PREPARING" : "REVIEW_REQUIRED";
    boolean alreadyAccepted = accepted && (exchange(value) ? EXCHANGE_ACCEPTED.contains(value.status())
      : Set.of("PREPARING", "REFUNDING", "RESTOCKING", "COMPLETED").contains(value.status()));
    if ("PASSED".equals(value.warehouseReturnStatus()) && note.equals(value.decision())
        && (alreadyAccepted || target.equals(value.status()))) return value;
    requireTransition(cases.acceptWarehouse(id, merchantId, target, note));
    return require(id);
  }

  @Transactional
  public LineAfterSale shipReplacement(Long merchantId, String id, String carrier, String tracking) {
    LineAfterSale value = merchantCase(merchantId, cases.lock(id));
    if (!exchange(value)) throw new BusinessRuleException("Only an exchange can ship a replacement");
    String trackingNumber = tracking.strip();
    if (returns.matchesReplacement(id, carrier, trackingNumber)) return value;
    requireTransition(cases.advance(id, "EXCHANGE_READY", "EXCHANGE_SHIPPING", null));
    var order = orders.find(value.customerId(), value.orderId())
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    returns.bindReplacement(id, value.orderId(), order.getOrderNumber(), carrier, trackingNumber);
    return require(id);
  }

  public LogisticsResponse customerReplacementTracking(Long customerId, String id) {
    if (!customerId.equals(require(id).customerId())) throw new ResourceNotFoundException("After-sales case not found");
    return returns.replacementTracking(id);
  }

  public LogisticsResponse merchantReplacementTracking(Long merchantId, String id) {
    owned(merchantId, id);
    return returns.replacementTracking(id);
  }

  public List<LineAfterSale> platformExchanges(Long orderId) {
    return cases.platformExchanges(orderId);
  }

  public ExchangeDetail platformExchange(String id) {
    var value = require(id);
    if (!exchange(value)) throw new ResourceNotFoundException("Exchange not found");
    return new ExchangeDetail(value, returns.tracking(id), returns.replacementTracking(id));
  }

  private InventoryLineCreditV1.Source requireOrigin(long orderId, long lineId, long merchantId, long productId,
                                                      Long warehouseId, String outboundId) {
    var sources = stock.sources(new InventoryLineCreditV1.Origin(orderId, lineId, merchantId, productId,
      warehouseId, outboundId));
    if (sources.size() != 1) {
      throw new BusinessRuleException("Choose an original outbound package for this order line and warehouse");
    }
    return sources.getFirst();
  }

  private void readWarehouse(LineAfterSale value) {
    if (!returns.delivered(value.id())) {
      cases.waitForWarehouse(value.id());
      return;
    }
    Long warehouseId = value.warehouseId();
    String outboundId = value.outboundId();
    if (warehouseId == null || outboundId == null) {
      try {
        var origin = requireOrigin(value.orderId(), value.orderLineId(), value.merchantId(), value.productId(),
          warehouseId, outboundId);
        warehouseId = origin.warehouseId();
        outboundId = origin.outboundId();
      } catch (BusinessRuleException failure) {
        cases.projectWarehouse(value.id(), warehouseId, outboundId, "ORIGIN_UNRESOLVED", null, "REVIEW_REQUIRED");
        return;
      }
    }
    var receipt = warehouse.submit(new WarehouseReturnClient.Plan(value.id(), outboundId, value.orderId(),
      value.orderLineId(), warehouseId, value.trackingNumber(), value.quantity()));
    String next = switch (receipt.status()) {
      case "WAITING_RECEIPT", "RECEIVED" -> "RETURN_SENT";
      case "PASSED" -> "WAITING_MERCHANT";
      default -> "REVIEW_REQUIRED";
    };
    if (receipt.status().equals(value.warehouseReturnStatus())) {
      cases.waitForWarehouse(value.id());
    } else {
      cases.projectWarehouse(value.id(), warehouseId, outboundId, receipt.status(), receipt.receivedQuantity(), next);
    }
  }

  @Scheduled(fixedDelayString = "${commerceflow.after-sales.dispatch-delay:2s}")
  public void dispatch() {
    for (LineAfterSale value : cases.due()) {
      try {
        switch (value.status()) {
          case "RETURN_SENT" -> readWarehouse(value);
          case "PREPARING" -> prepare(value);
          case "REFUNDING" -> pollRefund(value);
          case "RESTOCKING" -> restock(value);
          case "RELEASING" -> release(value);
          case "EXCHANGE_OUT_OF_STOCK" -> reserveReplacement(value);
          case "EXCHANGE_SHIPPING" -> confirmReplacement(value);
          default -> throw new IllegalStateException("Unexpected runnable after-sales state");
        }
      } catch (RuntimeException exception) {
        String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        cases.defer(value.id(), value.status(), error.substring(0, Math.min(error.length(), 300)));
      }
    }
  }

  private void prepare(LineAfterSale value) {
    if (!beforeShipment(value) && !qualifiedReturn(value)) {
      cases.advance(value.id(), value.status(), "REVIEW_REQUIRED", null);
      return;
    }
    if (exchange(value)) {
      reserveReplacement(value);
      return;
    }
    if (beforeShipment(value) && !admission.apply(orderCommand(value, "HOLD"))) {
      cases.advance(value.id(), value.status(), "FAILED", null);
      return;
    }
    if (!stock.credit(credit(value), false)) {
      cases.advance(value.id(), value.status(), beforeShipment(value) ? "RELEASING" : "REVIEW_REQUIRED", null);
      return;
    }
    if (value.total().signum() == 0) {
      cases.advance(value.id(), value.status(), "RESTOCKING", null);
      return;
    }
    paymentWrites.requireWritable();
    String id = value.id();
    String trace = id.replace("-", "");
    var receipt = payments.requestRefund(new RefundCreateCommandV1(1, id, value.orderId(), value.customerId(),
      id, "CUSTOMER_CANCELLED", value.reason(), id, id, "00-" + trace + "-" + trace.substring(0, 16) + "-01",
      id, value.total()));
    cases.advance(id, value.status(), "REFUNDING", receipt.refundNumber());
  }

  private void reserveReplacement(LineAfterSale value) {
    if (stock.exchange(credit(value), ExchangeAction.RESERVE)) {
      cases.advance(value.id(), value.status(), "EXCHANGE_READY", null);
    } else {
      if ("PREPARING".equals(value.status())) {
        cases.advance(value.id(), value.status(), "EXCHANGE_OUT_OF_STOCK", null);
      }
      cases.defer(value.id(), "EXCHANGE_OUT_OF_STOCK", "Replacement unavailable in the original warehouse");
    }
  }

  private void confirmReplacement(LineAfterSale value) {
    if (!returns.replacementRegistered(value.id())) {
      cases.defer(value.id(), value.status(), "Awaiting replacement shipment registration");
      return;
    }
    if (!stock.exchange(credit(value), ExchangeAction.SHIP)) {
      throw new IllegalStateException("Replacement shipment inventory needs reconciliation");
    }
    cases.advance(value.id(), value.status(), "COMPLETED", null);
  }

  private boolean qualifiedReturn(LineAfterSale value) {
    if (value.warehouseId() == null || value.outboundId() == null || value.receivedQuantity() == null) return false;
    if (value.approvedQuantity() != null) {
      return value.approvedMerchandiseAmount() != null && value.approvedQuantity() <= value.receivedQuantity();
    }
    return "PASSED".equals(value.warehouseReturnStatus()) && value.receivedQuantity() == value.quantity();
  }

  private void pollRefund(LineAfterSale value) {
    var refund = payments.refund(new RefundQueryV1(1, value.refundNumber())).orElse(null);
    if (refund != null && "SUCCEEDED".equals(refund.status()) && refund.amount().compareTo(value.total()) == 0
        && value.currency().equals(refund.currency())) {
      cases.advance(value.id(), value.status(), "RESTOCKING", null);
    } else if (refund != null && "FAILED".equals(refund.status())) {
      cases.advance(value.id(), value.status(), beforeShipment(value) ? "RELEASING" : "FAILED", null);
    } else {
      cases.defer(value.id(), value.status(), "Awaiting confirmed refund result");
    }
  }

  private void restock(LineAfterSale value) {
    if (!stock.credit(credit(value), true)) throw new IllegalStateException("Inventory credit needs reconciliation");
    if (beforeShipment(value) && !admission.apply(orderCommand(value, "SETTLE"))) {
      throw new IllegalStateException("Order line admission needs reconciliation");
    }
    cases.advance(value.id(), value.status(), "COMPLETED", null);
  }

  private void release(LineAfterSale value) {
    if (!admission.apply(orderCommand(value, "RELEASE"))) {
      throw new IllegalStateException("Refund hold needs reconciliation");
    }
    cases.advance(value.id(), value.status(), "FAILED", null);
  }

  private OrderLineRefundV1.Command orderCommand(LineAfterSale value, String action) {
    return new OrderLineRefundV1.Command(action, value.id(), value.orderId(), value.orderLineId(),
      value.customerId(), value.merchantId(), value.quantity(), value.warehouseId());
  }

  private InventoryLineCreditV1.Credit credit(LineAfterSale value) {
    return new InventoryLineCreditV1.Credit(value.id(), value.orderId(), value.orderLineId(),
      value.merchantId(), value.productId(), value.executableQuantity(), exchange(value) ? "RETURN" : value.kind(),
      value.warehouseId(), value.outboundId());
  }

  private boolean exchange(LineAfterSale value) {
    return "EXCHANGE".equals(value.kind());
  }

  private boolean beforeShipment(LineAfterSale value) {
    return "BEFORE_SHIPMENT".equals(value.kind());
  }

  private LineAfterSale require(String id) {
    LineAfterSale value = cases.find(id);
    if (value == null) throw new ResourceNotFoundException("After-sales case not found");
    return value;
  }

  private LineAfterSale owned(Long merchantId, String id) {
    return merchantCase(merchantId, require(id));
  }

  private LineAfterSale merchantCase(Long merchantId, LineAfterSale value) {
    if (value == null || !merchantId.equals(value.merchantId())) {
      throw new ResourceNotFoundException("After-sales case not found");
    }
    return value;
  }

  private void requireTransition(int updated) {
    if (updated != 1) throw new BusinessRuleException("After-sales case changed or does not allow this action");
  }

  public record ExchangeDetail(LineAfterSale exchange, LogisticsResponse returnTracking,
                               LogisticsResponse replacementTracking) {
  }

  public record Offer(Long productId, Long merchantId, int availableQuantity, String kind,
                      BigDecimal merchandiseAmount, BigDecimal shippingAmount, String currency,
                      Long warehouseId, String outboundId) {
  }
}
