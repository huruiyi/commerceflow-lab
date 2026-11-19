ALTER TABLE inventory_outbound_documents
  ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 COMMENT '实际出库仓库编号',
  ADD KEY idx_inventory_outbound_order_warehouse (order_id,warehouse_id),
  ADD CONSTRAINT fk_inventory_outbound_warehouse FOREIGN KEY (warehouse_id) REFERENCES inventory_warehouses(id);

ALTER TABLE inventory_line_credits
  ADD COLUMN warehouse_id BIGINT NULL COMMENT '回补仓，历史空值归入默认仓',
  ADD COLUMN outbound_id CHAR(36) NULL COMMENT '退货关联的原出库单',
  ADD CONSTRAINT fk_inventory_credit_warehouse FOREIGN KEY (warehouse_id) REFERENCES inventory_warehouses(id),
  ADD CONSTRAINT fk_inventory_credit_outbound FOREIGN KEY (outbound_id) REFERENCES inventory_outbound_documents(outbound_id);
