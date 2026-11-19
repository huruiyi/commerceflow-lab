ALTER TABLE line_after_sales
  ADD COLUMN warehouse_return_status VARCHAR(24) NULL,
  MODIFY COLUMN received_quantity INT NULL COMMENT '已确认的 WMS 实收件数，历史单保留原验收记录';
