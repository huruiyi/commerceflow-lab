package vip.fairy.commerceflow.commerce.fulfillment.application.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record FulfillmentCommandV1(@NotNull @Min(1) @Max(1) Integer version, @NotNull UUID commandId,
                                   @NotNull Operation operation, @Positive Long merchantId,
                                   @NotNull @Positive Long orderId, @NotNull Instant occurredAt,
                                   @Size(max = 100) String carrier, @Size(max = 120) String trackingNumber,
                                   @PositiveOrZero @Max(Long.MAX_VALUE - 1) Long expectedShipmentVersion,
                                   @Size(min = 1, max = 200) List<@NotNull @Valid PackageLine> lines,
                                   @Positive Long warehouseId) {

  public FulfillmentCommandV1 {
    carrier = carrier == null ? null : carrier.strip();
    trackingNumber = trackingNumber == null ? null : trackingNumber.strip();
  }

  public FulfillmentCommandV1 forMerchant(Long authenticatedMerchantId) {
    return new FulfillmentCommandV1(version, commandId, operation, authenticatedMerchantId, orderId, occurredAt,
      carrier, trackingNumber, expectedShipmentVersion, lines, warehouseId);
  }

  @AssertTrue(message = "Fields do not match the fulfillment operation")
  @JsonIgnore
  public boolean isOperationShapeValid() {
    if (operation == null || (operation != Operation.SHIP && lines != null)
        || (warehouseId != null && lines == null)) return false;
    if (operation == Operation.CONFIRM) {
      return carrier == null && trackingNumber == null && expectedShipmentVersion == null;
    }
    return carrier != null && !carrier.isBlank() && trackingNumber != null && !trackingNumber.isBlank()
      && (operation == Operation.UPDATE_SHIPMENT) == (expectedShipmentVersion != null);
  }

  public enum Operation {
    CONFIRM, SHIP, UPDATE_SHIPMENT
  }

  public record PackageLine(@Positive long orderLineId, @Positive int quantity) {
  }
}
