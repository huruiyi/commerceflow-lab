ALTER TABLE shop_orders
  ADD COLUMN inventory_status VARCHAR(40) NOT NULL DEFAULT 'WAITING_RESERVATION' AFTER status;

UPDATE shop_orders
SET inventory_status = CASE
  WHEN status = 'CANCELLED' THEN 'RELEASED'
  WHEN status = 'REFUNDED' THEN 'REFUNDED'
  WHEN status = 'PENDING_PAYMENT' THEN 'RESERVED'
  ELSE 'COMMITTED'
END;

ALTER TABLE shop_orders
  ADD CONSTRAINT chk_shop_order_inventory_status CHECK (
    inventory_status IN (
      'WAITING_RESERVATION','RESERVED','WAITING_COMMIT','COMMITTED','RELEASED',
      'REFUND_PENDING','REFUNDED','RECONCILIATION_REQUIRED'
    )
  );

ALTER TABLE refund_records
  ADD COLUMN inventory_status VARCHAR(40) NOT NULL DEFAULT 'NOT_STARTED' AFTER status;

UPDATE refund_records
SET inventory_status = CASE
  WHEN status = 'SUCCEEDED' THEN 'REFUNDED'
  ELSE 'NOT_STARTED'
END;

ALTER TABLE refund_records
  ADD CONSTRAINT chk_refund_inventory_status CHECK (
    inventory_status IN ('NOT_STARTED','WAITING','REFUNDED','RECONCILIATION_REQUIRED')
  );
