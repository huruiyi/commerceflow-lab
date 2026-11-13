package vip.fairy.commerceflow.commerce.payment.infrastructure;

public interface ReconciliationMapper {

  int detectExpired();
  long openIssues();
}
