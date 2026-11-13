ALTER TABLE shipment_records
  COMMENT = '模拟物流平台运单主表',
  MODIFY COLUMN merchant_shipment_no VARCHAR(100) NOT NULL COMMENT '商城传入的运单号',
  MODIFY COLUMN platform_shipment_no VARCHAR(100) NOT NULL COMMENT '模拟物流平台生成的运单号',
  MODIFY COLUMN order_number VARCHAR(64) NOT NULL COMMENT '商城对外展示的订单号',
  MODIFY COLUMN carrier VARCHAR(100) NOT NULL COMMENT '承运商代码或名称',
  MODIFY COLUMN tracking_number VARCHAR(120) NOT NULL COMMENT '承运商查件单号',
  MODIFY COLUMN status VARCHAR(40) NOT NULL COMMENT '物流运单当前状态',
  MODIFY COLUMN resume_status VARCHAR(40) NULL COMMENT '异常恢复后应回到的正常状态',
  MODIFY COLUMN callback_url VARCHAR(500) NOT NULL COMMENT '商城接收签名物流回调的 URL';

ALTER TABLE shipment_events
  COMMENT = '物流轨迹、异常与恢复事件',
  MODIFY COLUMN shipment_id BIGINT NOT NULL COMMENT '事件所属物流运单主键',
  MODIFY COLUMN event_id VARCHAR(36) NOT NULL COMMENT '跨系统回调去重的事件 UUID',
  MODIFY COLUMN event_type VARCHAR(30) NOT NULL DEFAULT 'PROGRESS' COMMENT '事件类型：PROGRESS、EXCEPTION 或 RECOVERY',
  MODIFY COLUMN status VARCHAR(40) NOT NULL COMMENT '事件发生后的物流状态',
  MODIFY COLUMN occurred_at TIMESTAMP(6) NOT NULL COMMENT '物流业务事件实际发生时刻';

ALTER TABLE callback_outbox
  COMMENT = '物流回调可靠投递 Outbox',
  MODIFY COLUMN shipment_id BIGINT NOT NULL COMMENT '回调所属物流运单主键',
  MODIFY COLUMN event_id VARCHAR(36) NOT NULL COMMENT '回调载荷中的物流事件 UUID',
  MODIFY COLUMN callback_url VARCHAR(500) NOT NULL COMMENT '本次投递的商城回调 URL 快照',
  MODIFY COLUMN payload JSON NOT NULL COMMENT '待签名投递的物流事件 JSON',
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'NEW' COMMENT 'Outbox 投递状态',
  MODIFY COLUMN attempts INT NOT NULL DEFAULT 0 COMMENT '已尝试投递次数',
  MODIFY COLUMN max_attempts INT NOT NULL COMMENT '进入 DEAD 前允许的最大投递次数',
  MODIFY COLUMN next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '下次允许投递的时刻',
  MODIFY COLUMN lease_until TIMESTAMP(6) NULL COMMENT '当前 Dispatcher 的领取租约到期时刻',
  MODIFY COLUMN claimed_by VARCHAR(100) NULL COMMENT '当前领取记录的 Dispatcher 实例标识',
  MODIFY COLUMN published_at TIMESTAMP(6) NULL COMMENT '回调首次成功投递的时刻';

ALTER TABLE signed_request_nonces
  COMMENT = '物流 API 签名请求防重放记录',
  MODIFY COLUMN app_id VARCHAR(100) NOT NULL COMMENT '调用物流 API 的应用标识',
  MODIFY COLUMN nonce VARCHAR(100) NOT NULL COMMENT '签名请求一次性随机值',
  MODIFY COLUMN request_timestamp BIGINT NOT NULL COMMENT '签名时的 Unix 毫秒时间戳';
