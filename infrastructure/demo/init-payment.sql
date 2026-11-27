BEGIN;
SELECT pg_advisory_xact_lock(hashtext('commerceflow.demo-payment'));
SELECT NOT EXISTS (SELECT 1 FROM payment_merchant_accounts)
  AND NOT EXISTS (SELECT 1 FROM payment_records)
  AND NOT EXISTS (SELECT 1 FROM refund_records) AS can_initialize \gset
\if :can_initialize
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
ON CONFLICT DO NOTHING;

\endif
COMMIT;
