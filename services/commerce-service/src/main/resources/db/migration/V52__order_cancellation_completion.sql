CREATE TABLE order_cancellation_completion (
  order_id BIGINT NOT NULL PRIMARY KEY COMMENT 'Order V1 order identifier',
  customer_id BIGINT NOT NULL COMMENT 'Notification recipient from the admitted order',
  actor_id BIGINT NULL COMMENT 'Customer actor, or NULL for scheduled cancellation',
  reason VARCHAR(300) NOT NULL COMMENT 'Cancellation reason used by both Outboxes',
  trigger_source VARCHAR(40) NOT NULL COMMENT 'CUSTOMER or SCHEDULED_TASK',
  scheduled_task_id BIGINT NULL COMMENT 'Payment timeout task that initiated cancellation',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at TIMESTAMP(6) NULL COMMENT 'Local completion recorded or superseded by the authoritative order state',
  KEY idx_order_cancellation_completion_due (completed_at,next_attempt_at,order_id)
);
