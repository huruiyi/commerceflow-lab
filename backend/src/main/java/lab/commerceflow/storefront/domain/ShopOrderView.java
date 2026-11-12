package lab.commerceflow.storefront.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class ShopOrderView {
  private Long id; private String orderNumber; private Long customerId; private String status;
  private BigDecimal totalAmount; private String currency; private Instant createdAt; private Instant updatedAt;
  private Instant confirmedAt; private Instant shippedAt; private String carrier; private String trackingNumber;
  private Instant paymentDueAt; private Instant cancelledAt; private String cancelReason;
  private Instant receivedAt; private Instant completedAt; private Instant logisticsDeliveredAt;
  public Long getId(){return id;} public String getOrderNumber(){return orderNumber;} public Long getCustomerId(){return customerId;}
  public String getStatus(){return status;} public BigDecimal getTotalAmount(){return totalAmount;} public String getCurrency(){return currency;}
  public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
  public Instant getConfirmedAt(){return confirmedAt;} public Instant getShippedAt(){return shippedAt;}
  public String getCarrier(){return carrier;} public String getTrackingNumber(){return trackingNumber;}
  public Instant getPaymentDueAt(){return paymentDueAt;} public Instant getCancelledAt(){return cancelledAt;}
  public String getCancelReason(){return cancelReason;} public Instant getReceivedAt(){return receivedAt;}
  public Instant getCompletedAt(){return completedAt;} public Instant getLogisticsDeliveredAt(){return logisticsDeliveredAt;}
}
