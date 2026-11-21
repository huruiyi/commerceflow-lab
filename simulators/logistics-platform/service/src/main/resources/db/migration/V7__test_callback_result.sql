ALTER TABLE shipment_records
  ADD COLUMN test_callback_result VARCHAR(16) NULL
    COMMENT '测试运单回调接收结果覆盖值，NULL 沿用创建时的结果',
  ADD CONSTRAINT ck_shipment_test_callback_result
    CHECK (test_callback_result IN ('ACCEPT', 'REJECT'));
