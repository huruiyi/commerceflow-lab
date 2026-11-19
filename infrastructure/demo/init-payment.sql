CREATE TEMPORARY TABLE demo_payment_guard (verified INT PRIMARY KEY);
INSERT INTO demo_payment_guard VALUES (0);
INSERT INTO demo_payment_guard VALUES (COALESCE(GET_LOCK('commerceflow.demo-payment', 30), 0));

START TRANSACTION;
SET @account_count = (SELECT COUNT(*) FROM payment_merchant_accounts);
SET @demo_count = (
  SELECT COUNT(*) FROM payment_merchant_accounts
  WHERE (channel, merchant_no) IN (
    ('ALIPAY', 'ALIPAY_MERCHANT_10001'),
    ('WECHAT_PAY', '1900001001'),
    ('UNION_PAY', '898310000000001')
  )
);
SET @can_initialize = @account_count = 0
  AND NOT EXISTS (SELECT 1 FROM payment_records)
  AND NOT EXISTS (SELECT 1 FROM refund_records);

-- Reject a non-empty merchant configuration and leave existing settings intact.
INSERT INTO demo_payment_guard
VALUES (IF(@can_initialize OR (@account_count = 3 AND @demo_count = 3), 2, 0));

INSERT INTO payment_merchant_accounts
  (channel, merchant_no, app_id, display_name, signing_algorithm, api_secret, created_at, updated_at)
SELECT demo.channel, demo.merchant_no, demo.app_id, demo.display_name, demo.signing_algorithm, demo.api_secret,
  CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM (
  SELECT 'ALIPAY' AS channel, 'ALIPAY_MERCHANT_10001' AS merchant_no, '2026111000000001' AS app_id,
    'CommerceFlow Alipay Merchant' AS display_name, 'RSA_SHA256' AS signing_algorithm,
    'demo-alipay-api-secret-not-for-production' AS api_secret
  UNION ALL
  SELECT 'WECHAT_PAY', '1900001001', 'wx202611100001', 'CommerceFlow WeChat Pay Merchant', 'HMAC_SHA256',
    'demo-wechat-api-secret-not-for-production'
  UNION ALL
  SELECT 'UNION_PAY', '898310000000001', 'UP202611100001', 'CommerceFlow UnionPay Merchant', 'RSA_SHA256',
    'demo-unionpay-api-secret-not-for-production'
) AS demo
WHERE @can_initialize;

COMMIT;
DO RELEASE_LOCK('commerceflow.demo-payment');
