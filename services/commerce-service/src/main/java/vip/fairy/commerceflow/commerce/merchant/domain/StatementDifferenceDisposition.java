package vip.fairy.commerceflow.commerce.merchant.domain;

import java.time.Instant;

/** Original business receipt and final decision for one reviewed statement difference. */
public record StatementDifferenceDisposition(String reviewId, String receiptRequestId, String receipt,
                                             Long linkedBy, Instant linkedAt, String closureRequestId,
                                             Long closedBy, Instant closedAt, String closureReason) {

  public enum Route {
    STOCKTAKE, PAYOUT, INVOICE, MANUAL;

    public static Route forKind(String kind) {
      return switch (kind) {
        case "INVENTORY_OUTBOUND_QUANTITY", "WMS_OUTBOUND_MISSING" -> STOCKTAKE;
        case "PAYOUT_FAILED", "PAYOUT_AMOUNT", "PAYOUT_CURRENCY", "MULTIPLE_PAYOUTS" -> PAYOUT;
        case "BLUE_REFUND_REFERENCE", "BLUE_AMOUNT", "BLUE_FACTS", "RED_FACTS",
             "RED_AMOUNT", "RED_NOT_CONFIRMED" -> INVOICE;
        default -> MANUAL;
      };
    }
  }

  public record View(Route route, String status, StatementDifferenceDisposition record) {
  }
}
