package vip.fairy.commerceflow.commerce.refund.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import vip.fairy.commerceflow.commerce.refund.domain.ReturnWorkOrder;
import vip.fairy.commerceflow.commerce.refund.domain.ReturnWorkOrder.Action;

@Mapper
public interface ReturnWorkOrderMapper {

  List<UnopenedCase> unopened();
  int open(String caseId);

  record UnopenedCase(String caseId, long orderId, long merchantId) {
  }
  List<ReturnWorkOrder> forMerchant(long merchantId);
  ReturnWorkOrder find(String caseId, long merchantId);
  List<Action> actions(String caseId);
  Action action(String requestId);
  void record(Action value);
  int advance(String caseId, String phase);
}
