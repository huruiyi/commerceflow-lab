ALTER TABLE fulfillment_write_admission
  ADD COLUMN write_owner ENUM('COMMERCE','FULFILLMENT') NOT NULL DEFAULT 'COMMERCE'
    COMMENT '履约流程写入所有者，切换与回退均要求冻结商城新受理',
  ADD CONSTRAINT chk_fulfillment_source_owner_frozen CHECK (write_owner = 'COMMERCE' OR frozen = TRUE);
