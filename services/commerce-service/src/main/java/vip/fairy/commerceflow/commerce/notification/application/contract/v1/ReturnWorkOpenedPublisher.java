package vip.fairy.commerceflow.commerce.notification.application.contract.v1;

public interface ReturnWorkOpenedPublisher {

  void opened(String caseId, long orderId, long merchantId);
}
