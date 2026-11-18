package vip.fairy.commerceflow.payment.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentMetricsMapper {

  long outboxPending();

  long commandOutboxPending();

  long processPending();

  long processRecoveryRequired();

  long orphanOpen();

  long refundProcessing();
}
