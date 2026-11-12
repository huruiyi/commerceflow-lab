package lab.commerceflow.storefront.scheduling;

public enum TaskTriggerSource {
  DATABASE_SCAN,
  DATABASE_RECONCILIATION,
  RABBITMQ,
  ROCKETMQ,
  ADMIN_RETRY
}
