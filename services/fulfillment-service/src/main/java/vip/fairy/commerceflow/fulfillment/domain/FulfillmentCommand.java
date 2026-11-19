package vip.fairy.commerceflow.fulfillment.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 受理标识、商家归属和原始运单参数。occurredAt 属于传输元数据。 */
public record FulfillmentCommand(Integer version, UUID commandId, Operation operation, Long merchantId,
                                 Long orderId, Instant occurredAt, String carrier, String trackingNumber,
                                 Long expectedShipmentVersion, List<PackageLine> lines) {

  public FulfillmentCommand {
    carrier = carrier == null ? null : carrier.strip();
    trackingNumber = trackingNumber == null ? null : trackingNumber.strip();
  }

  public void validate() {
    if (!Integer.valueOf(1).equals(version) || commandId == null || operation == null
        || merchantId == null || merchantId <= 0 || orderId == null || orderId <= 0 || occurredAt == null) {
      throw new IllegalArgumentException("Invalid fulfillment command");
    }
    if (lines != null) {
      if (operation != Operation.SHIP || lines.isEmpty() || lines.size() > 200) {
        throw new IllegalArgumentException("Invalid package lines");
      }
      var seen = new HashSet<Long>();
      for (PackageLine line : lines) {
        if (line == null || line.orderLineId() <= 0 || line.quantity() <= 0 || !seen.add(line.orderLineId())) {
          throw new IllegalArgumentException("Invalid or duplicate package line");
        }
      }
    }
    if (operation == Operation.CONFIRM) {
      if (carrier != null || trackingNumber != null || expectedShipmentVersion != null) {
        throw new IllegalArgumentException("Confirmation cannot carry shipment information");
      }
    } else if (carrier == null || carrier.isEmpty() || carrier.length() > 100
        || trackingNumber == null || trackingNumber.isEmpty() || trackingNumber.length() > 120
        || (operation == Operation.UPDATE_SHIPMENT) != (expectedShipmentVersion != null)
        || (expectedShipmentVersion != null
          && (expectedShipmentVersion < 0 || expectedShipmentVersion == Long.MAX_VALUE))) {
      throw new IllegalArgumentException("Invalid shipment information");
    }
  }

  public enum Operation {
    CONFIRM, SHIP, UPDATE_SHIPMENT
  }

  public record PackageLine(long orderLineId, int quantity) {
  }
}
