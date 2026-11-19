ALTER TABLE inventory_outbound_documents
  DROP INDEX uk_inventory_outbound_order,
  ADD INDEX idx_inventory_outbound_order (order_id);

ALTER TABLE inventory_outbound_lines
  DROP INDEX uk_inventory_outbound_order_line;
