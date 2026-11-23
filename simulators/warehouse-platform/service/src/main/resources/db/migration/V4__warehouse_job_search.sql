CREATE INDEX idx_warehouse_outbound_created
  ON warehouse_outbounds (created_at DESC, outbound_id);
CREATE INDEX idx_warehouse_outbound_status_created
  ON warehouse_outbounds (status, created_at DESC, outbound_id);
CREATE INDEX idx_warehouse_return_created
  ON warehouse_returns (created_at DESC, return_id);
CREATE INDEX idx_warehouse_return_status_created
  ON warehouse_returns (status, created_at DESC, return_id);
