ALTER TABLE shop_orders
  ADD COLUMN payment_due_at TIMESTAMP(6) NULL,
  ADD COLUMN cancelled_at TIMESTAMP(6) NULL,
  ADD COLUMN cancel_reason VARCHAR(300) NULL,
  ADD COLUMN received_at TIMESTAMP(6) NULL,
  ADD COLUMN completed_at TIMESTAMP(6) NULL,
  ADD COLUMN logistics_delivered_at TIMESTAMP(6) NULL;

UPDATE shop_orders
SET payment_due_at = DATE_ADD(created_at, INTERVAL 30 MINUTE)
WHERE payment_due_at IS NULL;

ALTER TABLE shop_orders
  MODIFY COLUMN payment_due_at TIMESTAMP(6) NOT NULL,
  ADD INDEX idx_shop_orders_payment_due (status, payment_due_at);

ALTER TABLE order_events
  MODIFY COLUMN actor_id BIGINT NULL,
  ADD COLUMN trigger_source VARCHAR(40) NOT NULL DEFAULT 'USER',
  ADD COLUMN scheduled_task_id BIGINT NULL,
  ADD INDEX idx_order_events_task (scheduled_task_id),
  ADD CONSTRAINT fk_order_events_scheduled_task
    FOREIGN KEY (scheduled_task_id) REFERENCES scheduled_tasks(id) ON DELETE SET NULL;
