package vip.fairy.commerceflow.commerce.refund.application;

import java.util.List;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementRefundV1;
import vip.fairy.commerceflow.commerce.refund.infrastructure.LineAfterSaleMapper;

@Component
public class SettlementRefundQuery implements SettlementRefundV1 {

  private final LineAfterSaleMapper cases;

  public SettlementRefundQuery(LineAfterSaleMapper cases) {
    this.cases = cases;
  }

  @Override
  public List<Refund> lockEvidence(Long orderId) {
    return cases.lockForOrder(orderId).stream().map(value -> new Refund(value.id(), value.orderLineId(),
      value.status(), value.merchandiseAmount(), value.shippingAmount(), value.refundNumber())).toList();
  }
}
