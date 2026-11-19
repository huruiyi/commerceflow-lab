package vip.fairy.commerceflow.commerce.ordering.application.contract.v1;

public interface OrderLineRefundV1 {

  boolean apply(Command command);

  record Command(String action, String caseId, Long orderId, Long orderLineId, Long customerId,
                 Long merchantId, int quantity) {
  }
}
