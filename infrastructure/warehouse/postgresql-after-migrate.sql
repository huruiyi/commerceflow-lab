GRANT SELECT ON warehouse_platform.warehouse_outbounds, warehouse_platform.warehouse_returns TO warehouse_runtime;
GRANT INSERT (outbound_id, order_id, merchant_id, warehouse_id, whole_order, planned),
      UPDATE (status, actual, decided_at) ON warehouse_platform.warehouse_outbounds TO warehouse_runtime;
GRANT INSERT (return_id, outbound_id, order_id, order_line_id, warehouse_id, tracking_number, expected_quantity),
      UPDATE (received_quantity, status, received_at, inspected_at, supplement_id, qualified_quantity,
              supplement_note, supplement_at) ON warehouse_platform.warehouse_returns TO warehouse_runtime;
