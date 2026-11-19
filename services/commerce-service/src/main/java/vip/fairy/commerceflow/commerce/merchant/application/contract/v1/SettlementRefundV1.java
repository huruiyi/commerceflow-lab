package vip.fairy.commerceflow.commerce.merchant.application.contract.v1;

import java.math.BigDecimal;
import java.util.List;

public interface SettlementRefundV1 {

  List<Refund> lockEvidence(Long orderId);

  record Refund(String id, Long line, String status, BigDecimal merchandise, BigDecimal freight, String number) {
  }
}
