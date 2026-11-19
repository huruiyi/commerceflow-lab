-- Existing orders charged the sum of their captured line subtotals.
CREATE TEMPORARY TABLE order_amount_migration_guard (
  violations BIGINT NOT NULL CHECK (violations = 0)
);
INSERT INTO order_amount_migration_guard
SELECT COUNT(*)
FROM shop_orders o
LEFT JOIN (
  SELECT order_id, SUM(subtotal) AS product_subtotal
  FROM order_items
  GROUP BY order_id
) i ON i.order_id = o.id
WHERE i.product_subtotal IS NULL OR o.total_amount <> i.product_subtotal;
DROP TEMPORARY TABLE order_amount_migration_guard;

ALTER TABLE shop_orders
  ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN shipping_fee DECIMAL(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN product_subtotal DECIMAL(12,2)
    GENERATED ALWAYS AS (total_amount + discount_amount - shipping_fee) STORED NOT NULL,
  ADD CONSTRAINT ck_order_amount_snapshot CHECK (
    total_amount >= 0 AND shipping_fee >= 0 AND discount_amount >= 0
    AND product_subtotal >= discount_amount
  );

ALTER TABLE order_items
  ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_order_item_discount CHECK (discount_amount >= 0 AND discount_amount <= subtotal);
