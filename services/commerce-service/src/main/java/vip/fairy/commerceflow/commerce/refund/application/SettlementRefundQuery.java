package vip.fairy.commerceflow.commerce.refund.application;

import java.util.List;
import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.SettlementRefundV1;
import vip.fairy.commerceflow.commerce.refund.domain.LineAfterSale;
import vip.fairy.commerceflow.commerce.refund.infrastructure.LineAfterSaleMapper;

@Component
public class SettlementRefundQuery implements SettlementRefundV1 {

  private final LineAfterSaleMapper cases;

  public SettlementRefundQuery(LineAfterSaleMapper cases) {
    this.cases = cases;
  }

  @Override
  public List<Refund> lockEvidence(Long orderId) {
    return cases.lockForOrder(orderId).stream().map(SettlementRefundQuery::refund).toList();
  }

  @Override
  public List<Refund> evidence(Long merchantId, Long orderId) {
    return cases.forMerchant(merchantId, orderId).stream().map(SettlementRefundQuery::refund).toList();
  }

  private static Refund refund(LineAfterSale value) {
    return new Refund(value.id(), value.orderLineId(), value.status(), value.executableMerchandiseAmount(),
      value.shippingAmount(), value.refundNumber(), value.executableQuantity());
  }
}
