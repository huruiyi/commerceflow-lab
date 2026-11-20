ALTER TABLE payment_merchant
  ADD COLUMN remark VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '商户中英文名称与用途备注';

UPDATE payment_merchant
SET display_name = '商流支付 · 支付宝模拟商户',
    remark = CONCAT('中文名称：商流支付 · 支付宝模拟商户', CHAR(10),
      '英文名称：CommerceFlow alipay', CHAR(10),
      '用途：供商城接入模拟支付宝渠道，验证收款、退款及结果回调。')
WHERE app_id = 'commerceflow-alipay' AND display_name = 'CommerceFlow alipay';

UPDATE payment_merchant
SET display_name = '商流支付 · 微信支付模拟商户',
    remark = CONCAT('中文名称：商流支付 · 微信支付模拟商户', CHAR(10),
      '英文名称：CommerceFlow wechat-pay', CHAR(10),
      '用途：供商城接入模拟微信支付渠道，验证收款、退款及结果回调。')
WHERE app_id = 'commerceflow-wechat-pay' AND display_name = 'CommerceFlow wechat-pay';

UPDATE payment_merchant
SET display_name = '商流支付 · 银联模拟商户',
    remark = CONCAT('中文名称：商流支付 · 银联模拟商户', CHAR(10),
      '英文名称：CommerceFlow union-pay', CHAR(10),
      '用途：供商城接入模拟银联渠道，验证收款、退款及结果回调。')
WHERE app_id = 'commerceflow-union-pay' AND display_name = 'CommerceFlow union-pay';

UPDATE payment_merchant
SET display_name = '商流支付 · 商家打款模拟应用',
    remark = CONCAT('中文名称：商流支付 · 商家打款模拟应用', CHAR(10),
      '英文名称：CommerceFlow payout', CHAR(10),
      '用途：商城结算发起模拟商家打款、查询打款状态及验证结果回调的专用应用。')
WHERE app_id = 'commerceflow-payout' AND display_name = 'CommerceFlow payout';

UPDATE payment_merchant
SET remark = CONCAT('中文名称：05A-3 HMAC 签名安全测试商户 ', SUBSTRING_INDEX(display_name, ' ', -1), CHAR(10),
      '英文名称：', display_name, CHAR(10),
      '用途：验证 HMAC-SHA256 请求签名、篡改与重放拒绝，以及凭据轮换。'),
    display_name = CONCAT('05A-3 HMAC 签名安全测试商户 ', SUBSTRING_INDEX(display_name, ' ', -1))
WHERE LEFT(app_id, 4) = 'edu_' AND signing_algorithm = 'HMAC_SHA256'
  AND display_name REGEXP '^05A-3 Security Merchant [0-9]+$';

UPDATE payment_merchant
SET remark = CONCAT('中文名称：05A-3 RSA 签名测试商户 ', SUBSTRING_INDEX(display_name, ' ', -1), CHAR(10),
      '英文名称：', display_name, CHAR(10),
      '用途：验证登记商户公钥以及使用 RSA-SHA256 签名发起渠道交易。'),
    display_name = CONCAT('05A-3 RSA 签名测试商户 ', SUBSTRING_INDEX(display_name, ' ', -1))
WHERE LEFT(app_id, 4) = 'edu_' AND signing_algorithm = 'RSA_SHA256'
  AND display_name REGEXP '^05A-3 RSA Merchant [0-9]+$';
