package vip.fairy.commerceflow.commerce.identity.application.contract.v1;

public interface InvoiceAccessV1 {

  boolean enabled();

  boolean canOperate(long accountId);

  long requireMerchant(long accountId);

  void requireOperator(long accountId);
}
