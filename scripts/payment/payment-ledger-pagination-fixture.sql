CREATE TEMPORARY TABLE report_page_fixture (
  order_id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  report_date DATE NOT NULL,
  currency CHAR(3) NOT NULL,
  amount DECIMAL(16,2) NOT NULL
);

INSERT INTO report_page_fixture (order_id, merchant_id, report_date, currency, amount)
WITH RECURSIVE report_days AS (
  SELECT 1 AS day_number
  UNION ALL
  SELECT day_number + 1 FROM report_days WHERE day_number < 26
)
SELECT 520 + day_number, 10001, DATE_ADD('2025-04-01', INTERVAL (day_number - 1) DAY), 'CNY', 12.50
FROM report_days;

INSERT INTO report_page_fixture (order_id, merchant_id, report_date, currency, amount)
VALUES (547,10002,'2025-04-04','CNY',3.00), (548,10002,'2025-04-16','CNY',3.00),
       (549,10002,'2025-04-26','CNY',3.00), (550,10002,'2025-04-26','USD',7.00);

INSERT INTO orders.shop_orders (id, order_number, customer_id, status, inventory_status, total_amount,
                                currency, created_at, updated_at, payment_due_at)
SELECT order_id, CONCAT('REPORT-O', order_id), 1, 'PAID', 'RESERVED', amount,
       currency, report_date + INTERVAL 2 HOUR, report_date + INTERVAL 2 HOUR, report_date + INTERVAL 3 HOUR
FROM report_page_fixture;

INSERT INTO orders.order_items (order_id, product_id, merchant_id, product_name, thumbnail_url,
                                unit_price, quantity, subtotal)
SELECT order_id, merchant_id - 10000, merchant_id, 'Pagination fixture', '', amount, 1, amount
FROM report_page_fixture;

INSERT INTO payments.payment_records (order_id, payment_number, idempotency_key, amount, status,
                                      failure_reason, created_at, merchant_account_id, channel, currency,
                                      request_fingerprint, channel_transaction_no, paid_at, updated_at,
                                      version, refunded_amount)
SELECT order_id, CONCAT('REPORT-P', order_id), CONCAT('p', order_id), amount, 'SUCCESS', '',
       report_date + INTERVAL 2 HOUR, IF(currency = 'USD', 2, 1), IF(currency = 'USD', 'UNION_PAY', 'ALIPAY'),
       currency, CONCAT('p', order_id), CONCAT('C', order_id), report_date + INTERVAL 121 MINUTE,
       report_date + INTERVAL 121 MINUTE, 0, IF(order_id = 546, 4.00, 0.00)
FROM report_page_fixture;

INSERT INTO payments.refund_records (refund_number, order_id, payment_id, customer_id, idempotency_key,
                                     request_fingerprint, amount, currency, reason_code, reason_detail,
                                     status, inventory_status, order_status_before_refund, failure_reason,
                                     requested_at, succeeded_at, created_at, updated_at, version)
SELECT 'REPORT-R546', order_id, id, 1, 'r546', 'r546', refunded_amount, currency, 'TEST', '', 'SUCCEEDED',
       'NOT_STARTED', 'PAID', '', '2025-04-26 03:00:00', '2025-04-26 03:01:00',
       '2025-04-26 03:00:00', '2025-04-26 03:01:00', 0
FROM payments.payment_records WHERE order_id = 546;
