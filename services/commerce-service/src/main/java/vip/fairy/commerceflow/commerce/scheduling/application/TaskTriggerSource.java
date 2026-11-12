package vip.fairy.commerceflow.commerce.scheduling.application;

public enum TaskTriggerSource {
  DATABASE_SCAN,
  DATABASE_RECONCILIATION,
  RABBITMQ,
  ROCKETMQ,
  ADMIN_RETRY
}
