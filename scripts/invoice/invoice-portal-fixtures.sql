USE commerceflow;

INSERT INTO app_users (id,email,password_hash,first_name,last_name,phone,role,enabled)
SELECT account.id, account.email, u.password_hash, 'Invoice', account.label, '', account.account_role, TRUE
FROM app_users u CROSS JOIN (
  SELECT 92001 AS id, 'invoice-store-a@example.test' AS email, 'Store A' AS label, 'MERCHANT' AS account_role
  UNION ALL SELECT 92002, 'invoice-store-b@example.test', 'Store B', 'MERCHANT'
  UNION ALL SELECT 92003, 'invoice-operator@example.test', 'Operator', 'ADMIN'
  UNION ALL SELECT 92004, 'invoice-denied@example.test', 'No access', 'ADMIN'
) account WHERE u.email = 'demo@example.com';

INSERT INTO user_roles (user_id,role_id)
SELECT u.id,r.id FROM app_users u JOIN roles r ON r.code=u.role WHERE u.id BETWEEN 92001 AND 92004;

INSERT INTO user_roles (user_id,role_id)
SELECT 92003,id FROM roles WHERE code='INVOICE_OPERATIONS';

INSERT INTO merchant_stores (merchant_id,name,description,status)
VALUES (92001,'Invoice Store A','','ACTIVE'), (92002,'Invoice Store B','','ACTIVE');

USE orders;

INSERT INTO shop_orders (id,order_number,customer_id,status,inventory_status,total_amount,currency,
  created_at,updated_at,payment_due_at)
VALUES
  (9201,'INVOICE-STORE-A',1,'PAID','COMMITTED',120.00,'CNY',NOW(6),NOW(6),NOW(6)),
  (9202,'INVOICE-STORE-B',9102,'PAID','COMMITTED',180.00,'CNY',NOW(6),NOW(6),NOW(6)),
  (9203,'INVOICE-REVIEW',1,'PAID','COMMITTED',70.00,'CNY',NOW(6),NOW(6),NOW(6)),
  (9204,'INVOICE-FAILED',9102,'PAID','COMMITTED',90.00,'CNY',NOW(6),NOW(6),NOW(6));

INSERT INTO order_items (order_id,product_id,merchant_id,product_name,thumbnail_url,unit_price,quantity,subtotal)
VALUES
  (9201,1,92001,'Item Store A','',120.00,1,120.00),
  (9202,2,92002,'Item Store B','',180.00,1,180.00),
  (9203,1,92001,'Item for reconciliation','',70.00,1,70.00),
  (9204,2,92002,'Item for failed issuance','',90.00,1,90.00);

USE payments;

INSERT INTO payment_records (order_id,payment_number,idempotency_key,amount,status,failure_reason,created_at,
  merchant_account_id,channel,currency,request_fingerprint,channel_transaction_no,updated_at,version,refunded_amount)
VALUES
  (9201,'INVOICE-P9201','p9201',120.00,'PENDING','',NOW(6),1,'ALIPAY','CNY','p9201','CH-P9201',NOW(6),0,0),
  (9202,'INVOICE-P9202','p9202',180.00,'PENDING','',NOW(6),1,'ALIPAY','CNY','p9202','CH-P9202',NOW(6),0,0),
  (9203,'INVOICE-P9203','p9203',71.00,'PENDING','',NOW(6),1,'ALIPAY','CNY','p9203','CH-P9203',NOW(6),0,0),
  (9204,'INVOICE-P9204','p9204',90.00,'PENDING','',NOW(6),1,'ALIPAY','CNY','p9204','CH-P9204',NOW(6),0,0);

INSERT INTO refund_records (refund_number,order_id,payment_id,customer_id,idempotency_key,request_fingerprint,
  amount,currency,reason_code,reason_detail,status,inventory_status,order_status_before_refund,failure_reason,
  requested_at,created_at,updated_at,version)
SELECT 'INVOICE-STORE-B-REFUND',9202,id,9102,'p9202-r1','p9202-r1',30.00,'CNY',
  'INVOICE_QA','Browser red invoice','PROCESSING','WAITING','PAID','',NOW(6),NOW(6),NOW(6),0
FROM payment_records WHERE order_id=9202;
