ALTER TABLE fulfillment_shipment_jobs
  ADD COLUMN package_lines TEXT NULL COMMENT '该包裹受理的订单行 ID 和数量，按订单行编号排序的 JSON',
  ADD CHECK (package_lines IS NULL OR JSON_VALID(package_lines));
