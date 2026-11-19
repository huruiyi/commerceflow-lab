ALTER TABLE order_package_allocations
  ADD COLUMN warehouse_receipt_at TIMESTAMP(6) NULL,
  ADD COLUMN voided_at TIMESTAMP(6) NULL;

ALTER TABLE order_package_lines
  ADD COLUMN actual_quantity INT NULL,
  ADD CONSTRAINT chk_package_actual_quantity
    CHECK (actual_quantity IS NULL OR actual_quantity BETWEEN 0 AND quantity);
