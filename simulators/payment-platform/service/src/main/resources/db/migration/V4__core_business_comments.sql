ALTER TABLE channel_transaction
  COMMENT = '模拟支付渠道交易单',
  MODIFY COLUMN channel_transaction_no VARCHAR(80) NOT NULL COMMENT '模拟渠道生成的交易号',
  MODIFY COLUMN merchant_id BIGINT NOT NULL COMMENT '发起交易的支付平台商户主键',
  MODIFY COLUMN merchant_request_no VARCHAR(100) NOT NULL COMMENT '商城传入的支付请求号',
  MODIFY COLUMN channel VARCHAR(30) NOT NULL COMMENT '模拟收银渠道代码',
  MODIFY COLUMN amount DECIMAL(19,2) NOT NULL COMMENT '渠道交易金额，币种见 currency',
  MODIFY COLUMN currency VARCHAR(3) NOT NULL COMMENT 'ISO 4217 交易币种代码',
  MODIFY COLUMN status VARCHAR(30) NOT NULL COMMENT '渠道交易状态',
  MODIFY COLUMN fault_scenario VARCHAR(40) NOT NULL DEFAULT 'NONE' COMMENT '学习实验启用的故障注入场景',
  MODIFY COLUMN expires_at TIMESTAMP(6) NOT NULL COMMENT '渠道交易过期时刻',
  MODIFY COLUMN paid_at TIMESTAMP(6) NULL COMMENT '渠道交易成功时刻',
  MODIFY COLUMN closed_at TIMESTAMP(6) NULL COMMENT '待支付渠道交易被关闭的时刻';

ALTER TABLE channel_refund
  COMMENT = '模拟支付渠道退款单',
  MODIFY COLUMN channel_refund_no VARCHAR(100) NOT NULL COMMENT '模拟渠道生成的退款号',
  MODIFY COLUMN merchant_id BIGINT NOT NULL COMMENT '发起退款的支付平台商户主键',
  MODIFY COLUMN merchant_refund_no VARCHAR(100) NOT NULL COMMENT '商城传入的退款请求号',
  MODIFY COLUMN channel_transaction_no VARCHAR(80) NOT NULL COMMENT '被退款的渠道交易号',
  MODIFY COLUMN amount DECIMAL(19,2) NOT NULL COMMENT '渠道退款金额，币种见 currency',
  MODIFY COLUMN currency VARCHAR(3) NOT NULL COMMENT 'ISO 4217 退款币种代码',
  MODIFY COLUMN status VARCHAR(30) NOT NULL COMMENT '渠道退款状态',
  MODIFY COLUMN fault_scenario VARCHAR(40) NOT NULL DEFAULT 'NONE' COMMENT '学习实验启用的故障注入场景',
  MODIFY COLUMN succeeded_at TIMESTAMP(6) NULL COMMENT '渠道退款成功时刻',
  MODIFY COLUMN failed_at TIMESTAMP(6) NULL COMMENT '渠道退款最终失败时刻';

ALTER TABLE payment_merchant
  COMMENT = '模拟支付平台商户、签名凭据与回调配置',
  MODIFY COLUMN merchant_no VARCHAR(64) NOT NULL COMMENT '支付平台分配的商户号',
  MODIFY COLUMN app_id VARCHAR(64) NOT NULL COMMENT '签名请求使用的应用标识',
  MODIFY COLUMN secret_cipher VARCHAR(512) NOT NULL COMMENT '加密存储的 HMAC 商户密钥',
  MODIFY COLUMN credential_version INT NOT NULL DEFAULT 1 COMMENT '当前签名凭据版本',
  MODIFY COLUMN signing_algorithm VARCHAR(32) NOT NULL DEFAULT 'HMAC_SHA256' COMMENT '商户请求签名算法',
  MODIFY COLUMN merchant_public_key TEXT NULL COMMENT 'RSA 签名商户公钥，HMAC 商户为空',
  MODIFY COLUMN notify_url VARCHAR(500) NULL COMMENT '支付和退款结果默认回调地址',
  MODIFY COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '商户是否允许发起新请求';

ALTER TABLE signed_request_record
  COMMENT = '商户签名请求防重放记录',
  MODIFY COLUMN merchant_id BIGINT NOT NULL COMMENT '已验签商户主键',
  MODIFY COLUMN request_id VARCHAR(80) NOT NULL COMMENT '商户请求唯一标识',
  MODIFY COLUMN nonce VARCHAR(100) NOT NULL COMMENT '签名请求一次性随机值',
  MODIFY COLUMN request_timestamp BIGINT NOT NULL COMMENT '签名时的 Unix 毫秒时间戳',
  MODIFY COLUMN body_sha256 VARCHAR(64) NOT NULL COMMENT '请求体 SHA-256 十六进制摘要',
  MODIFY COLUMN signing_algorithm VARCHAR(32) NOT NULL COMMENT '本次请求实际使用的签名算法',
  MODIFY COLUMN request_path VARCHAR(300) NOT NULL COMMENT '参与签名的 HTTP 请求路径';

ALTER TABLE merchant_callback_endpoint
  COMMENT = '商户可用的签名异步回调端点',
  MODIFY COLUMN merchant_id BIGINT NOT NULL COMMENT '回调端点所属商户主键',
  MODIFY COLUMN callback_url VARCHAR(500) NOT NULL COMMENT '允许投递签名通知的 URL',
  MODIFY COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '回调端点是否启用';
