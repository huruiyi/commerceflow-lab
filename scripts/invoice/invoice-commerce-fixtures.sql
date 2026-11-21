USE orders;

INSERT INTO shop_orders (id, order_number, customer_id, status, inventory_status, total_amount, currency,
  created_at, updated_at, payment_due_at, shipping_fee, discount_amount, coupon_id)
VALUES
  (9101, 'INVOICE-O9101', 1, 'PAID', 'COMMITTED', 300.00, 'CNY', NOW(6), NOW(6), NOW(6), 20.00, 40.00, 'INVOICE-COUPON'),
  (9102, 'INVOICE-O9102', 1, 'REFUNDED', 'COMMITTED', 40.00, 'CNY', NOW(6), NOW(6), NOW(6), 0.00, 0.00, NULL),
  (9103, 'INVOICE-O9103', 1, 'PAID', 'COMMITTED', 50.00, 'CNY', NOW(6), NOW(6), NOW(6), 0.00, 0.00, NULL),
  (9104, 'INVOICE-O9104', 1, 'PAID', 'COMMITTED', 100.00, 'CNY', NOW(6), NOW(6), NOW(6), 0.00, 0.00, NULL),
  (9105, 'INVOICE-O9105', 1, 'PENDING_PAYMENT', 'RESERVED', 100.00, 'CNY', NOW(6), NOW(6), NOW(6), 0.00, 0.00, NULL);

INSERT INTO order_items (order_id, product_id, merchant_id, product_name, thumbnail_url, unit_price, quantity,
  subtotal, discount_amount)
VALUES
  (9101, 1, 1, 'A', '', 60.00, 1, 60.00, 10.00),
  (9101, 2, 1, 'B', '', 90.00, 1, 90.00, 10.00),
  (9101, 3, 1, 'C', '', 110.00, 1, 110.00, 10.00),
  (9101, 4, 1, 'D', '', 60.00, 1, 60.00, 10.00),
  (9102, 1, 1, 'E', '', 40.00, 1, 40.00, 0.00),
  (9103, 1, 1, 'F', '', 50.00, 1, 50.00, 0.00),
  (9104, 1, 1, 'G', '', 100.00, 1, 100.00, 0.00),
  (9105, 1, 1, 'H', '', 100.00, 1, 100.00, 0.00);

USE payments;

INSERT INTO payment_merchant_accounts (id, channel, merchant_no, app_id, display_name, signing_algorithm,
  api_secret, enabled, created_at, updated_at)
VALUES (1, 'ALIPAY', 'CFL-ALIPAY', 'commerceflow-alipay', 'Invoice verification', 'HMAC_SHA256', 'unused', TRUE, NOW(6), NOW(6));

INSERT INTO payment_records (order_id, payment_number, idempotency_key, amount, status, failure_reason, created_at,
  merchant_account_id, channel, currency, request_fingerprint, channel_transaction_no, updated_at, version, refunded_amount)
VALUES
  (9101, 'INVOICE-P9101', 'p9101', 300.00, 'PENDING', '', NOW(6), 1, 'ALIPAY', 'CNY', 'p9101', 'CH-P9101', NOW(6), 0, 0),
  (9102, 'INVOICE-P9102', 'p9102', 40.00, 'PENDING', '', NOW(6), 1, 'ALIPAY', 'CNY', 'p9102', 'CH-P9102', NOW(6), 0, 0),
  (9103, 'INVOICE-P9103', 'p9103', 51.00, 'PENDING', '', NOW(6), 1, 'ALIPAY', 'CNY', 'p9103', 'CH-P9103', NOW(6), 0, 0),
  (9104, 'INVOICE-P9104', 'p9104', 100.00, 'PENDING', '', NOW(6), 1, 'ALIPAY', 'CNY', 'p9104', 'CH-P9104', NOW(6), 0, 0);

INSERT INTO refund_records (refund_number, order_id, payment_id, customer_id, idempotency_key, request_fingerprint, amount,
  currency, reason_code, reason_detail, status, inventory_status, order_status_before_refund, failure_reason,
  requested_at, created_at, updated_at, version)
SELECT f.refund_no, p.order_id, p.id, 1, f.refund_no, f.refund_no, f.amount, p.currency,
  'INVOICE_QA', f.detail, 'PROCESSING', 'WAITING', 'PAID', '', NOW(6), NOW(6), NOW(6), 0
FROM payment_records p JOIN (
  SELECT 9101 AS order_id, 'INVOICE-R-PRE' AS refund_no, 50.00 AS amount, 'Merchandise 50.00' AS detail
  UNION ALL SELECT 9101, 'INVOICE-R-DURING', 80.00, 'Merchandise 80.00'
  UNION ALL SELECT 9101, 'INVOICE-R-AFTER', 100.00, 'Merchandise 100.00'
  UNION ALL SELECT 9101, 'INVOICE-R-FINAL', 70.00, 'Merchandise 50.00 and freight 20.00'
  UNION ALL SELECT 9101, 'INVOICE-R-FAILED', 3.00, 'Failed refund'
  UNION ALL SELECT 9101, 'INVOICE-R-PENDING', 2.00, 'Still processing'
  UNION ALL SELECT 9102, 'INVOICE-R-FULL-PRE', 40.00, 'Full refund before issuance'
  UNION ALL SELECT 9104, 'INVOICE-R-RETRY', 25.00, 'Refund before retrying confirmed failed issuance'
) f ON f.order_id = p.order_id;

USE commerceflow;

-- These historical line snapshots reserve no work for the after-sales scheduler.
INSERT INTO line_after_sales (id, order_id, order_line_id, product_id, customer_id, merchant_id, quantity,
  merchandise_amount, shipping_amount, currency, kind, status, reason, refund_number)
SELECT UUID(), i.order_id, i.id, i.product_id, 1, i.merchant_id, i.quantity, i.subtotal - i.discount_amount,
  CASE WHEN i.product_id = 4 THEN o.shipping_fee ELSE 0 END,
  o.currency, 'BEFORE_SHIPMENT', 'COMPLETED', 'Invoice verification price snapshot', f.refund_no
FROM orders.order_items i JOIN orders.shop_orders o ON o.id = i.order_id JOIN (
  SELECT 1 AS product_id, 'INVOICE-R-PRE' AS refund_no
  UNION ALL SELECT 2, 'INVOICE-R-DURING'
  UNION ALL SELECT 3, 'INVOICE-R-AFTER'
  UNION ALL SELECT 4, 'INVOICE-R-FINAL'
) f ON f.product_id = i.product_id WHERE i.order_id = 9101;

UPDATE payments.refund_records r JOIN line_after_sales a ON a.refund_number = r.refund_number
SET r.after_sale_id = a.id, r.amount = a.merchandise_amount + a.shipping_amount;

INSERT INTO app_users (id, email, password_hash, first_name, last_name, phone, role, enabled)
SELECT 9102, 'invoice-other@example.test', password_hash, 'Invoice', 'Other', '', 'CUSTOMER', TRUE
FROM app_users WHERE email = 'demo@example.com';

INSERT INTO user_roles (user_id, role_id) SELECT 9102, id FROM roles WHERE code = 'CUSTOMER';
