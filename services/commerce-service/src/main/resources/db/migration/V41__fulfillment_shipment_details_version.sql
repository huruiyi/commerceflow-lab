ALTER TABLE shipment_records
  ADD COLUMN shipment_version BIGINT NOT NULL DEFAULT 0 COMMENT '商城物流投影的运单详情版本';
