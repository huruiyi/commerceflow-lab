package vip.fairy.commerceflow.commerce.refund.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record ReturnWorkOrder(String caseId, long orderId, long orderLineId, Long warehouseId, String outboundId,
                              String trackingNumber, String warehouseStatus, String phase, String caseStatus,
                              int requestedQuantity, Integer receivedQuantity, Integer approvedQuantity,
                              BigDecimal approvedAmount, String currency, String refundNumber, String kind) {

  public record Action(String requestId, String caseId, long actorId, String action, String note,
                       Integer quantity, String evidenceId, Instant createdAt) {
  }
}
