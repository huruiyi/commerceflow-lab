ALTER TABLE fulfillment_shipment_jobs
  ADD COLUMN warehouse_id BIGINT NULL COMMENT '当前包裹的仓库，存量默认仓可为空';
