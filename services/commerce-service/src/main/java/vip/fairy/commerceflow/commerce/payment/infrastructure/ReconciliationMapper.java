package vip.fairy.commerceflow.commerce.payment.infrastructure;

import org.apache.ibatis.annotations.*;

public interface ReconciliationMapper {

  int detectExpired();


  long openIssues();
}
