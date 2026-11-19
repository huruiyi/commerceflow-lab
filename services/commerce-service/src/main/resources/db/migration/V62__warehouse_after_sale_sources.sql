ALTER TABLE line_after_sales
  ADD COLUMN warehouse_id BIGINT NULL COMMENT '按仓售后关联的原仓',
  ADD COLUMN outbound_id CHAR(36) NULL COMMENT '退货所对应的包裹和库存出库单';
