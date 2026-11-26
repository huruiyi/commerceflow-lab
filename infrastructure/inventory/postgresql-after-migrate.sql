GRANT SELECT ON exchange_replacement_reservations, inventory_catalog_event_inbox, inventory_command_inbox,
  inventory_delivered_reconciliation, inventory_delivered_reconciliation_lines, inventory_line_credits,
  inventory_movements, inventory_outbound_documents, inventory_outbound_event_outbox, inventory_outbound_lines,
  inventory_reservations, inventory_stocktake_orders, inventory_stocktake_reconciliation,
  inventory_warehouse_stock, inventory_warehouses, product_inventory TO inventory_runtime;

GRANT INSERT (product_id,merchant_id,product_name,thumbnail_url,unit_price,catalog_lifecycle_status,
    catalog_business_version,stock_available,stock_reserved,stock_sold),
  UPDATE (merchant_id,product_name,thumbnail_url,unit_price,catalog_lifecycle_status,catalog_business_version,
    stock_available,stock_reserved,stock_sold) ON product_inventory TO inventory_runtime;
GRANT INSERT (product_id,warehouse_id,stock_available,stock_reserved,stock_sold),
  UPDATE (stock_available,stock_reserved,stock_sold) ON inventory_warehouse_stock TO inventory_runtime;
GRANT INSERT (order_id,product_id,quantity,status,warehouse_id,committed_at),
  UPDATE (status,version,committed_at,released_at) ON inventory_reservations TO inventory_runtime;
GRANT INSERT (command_id,command_type,order_id,payload_fingerprint,status),
  UPDATE (status,processed_at,rejection_message) ON inventory_command_inbox TO inventory_runtime;
GRANT INSERT (event_id,product_id,business_version,payload_fingerprint,status),
  UPDATE (status,processed_at) ON inventory_catalog_event_inbox TO inventory_runtime;
GRANT INSERT (event_id,outbound_id,order_line_id,payload), UPDATE (published_at,next_attempt_at)
  ON inventory_outbound_event_outbox TO inventory_runtime;
GRANT INSERT (case_id,status), UPDATE (status) ON exchange_replacement_reservations TO inventory_runtime;
GRANT INSERT (outbound_id,order_line_id,product_id,quantity) ON inventory_outbound_lines TO inventory_runtime;
GRANT INSERT (order_id,reference) ON inventory_stocktake_orders TO inventory_runtime;
GRANT INSERT (reference,fingerprint,audit_data,adjustment), UPDATE (movement_id)
  ON inventory_stocktake_reconciliation TO inventory_runtime;

-- SELECT FOR UPDATE requires UPDATE on at least one column of the locked table.
GRANT INSERT (event_id,order_id,product_id,movement_type,quantity,available_after,reserved_after,
    sold_after,detail,warehouse_id), UPDATE (id) ON inventory_movements TO inventory_runtime;
GRANT INSERT (case_id,order_id,order_line_id,merchant_id,product_id,quantity,kind,warehouse_id,outbound_id),
  UPDATE (case_id) ON inventory_line_credits TO inventory_runtime;
GRANT INSERT (outbound_id,order_id,merchant_id,payload_fingerprint,warehouse_id), UPDATE (outbound_id)
  ON inventory_outbound_documents TO inventory_runtime;
GRANT INSERT (order_id,evidence_fingerprint,recorded_by), UPDATE (order_id)
  ON inventory_delivered_reconciliation TO inventory_runtime;
GRANT INSERT (order_id,product_id,quantity,warehouse_reference,evidence_at,evidence_note,movement_id,disposition),
  UPDATE (order_id) ON inventory_delivered_reconciliation_lines TO inventory_runtime;
