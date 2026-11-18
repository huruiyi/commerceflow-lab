ALTER TABLE shipment_records
  ADD COLUMN shipment_version BIGINT NOT NULL DEFAULT 0 COMMENT '运单详情版本，仅在修改承运商或运单号时递增';
