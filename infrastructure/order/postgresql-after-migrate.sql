GRANT SELECT ON shop_orders, order_events, order_items, order_shipping_addresses,
  order_line_refund_admissions, order_package_allocations, order_package_lines, order_warehouse_amounts,
  order_remote_message_inbox, order_process_managers, order_command_outbox, order_result_inbox TO order_runtime;

GRANT INSERT (order_number,customer_id,status,inventory_status,total_amount,currency,payment_due_at,created_at,updated_at,
    shipping_fee,discount_amount,coupon_id),
  UPDATE (status,inventory_status,updated_at,confirmed_at,shipped_at,carrier,tracking_number,cancelled_at,cancel_reason,
    received_at,completed_at,logistics_delivered_at,shipment_intent_key,shipment_version) ON shop_orders TO order_runtime;
GRANT INSERT (order_id,actor_id,event_type,from_status,to_status,detail,trigger_source,scheduled_task_id,created_at)
  ON order_events TO order_runtime;
GRANT INSERT (order_id,product_id,merchant_id,product_name,thumbnail_url,unit_price,quantity,subtotal,discount_amount)
  ON order_items TO order_runtime;
GRANT INSERT ON order_shipping_addresses, order_warehouse_amounts TO order_runtime;
GRANT INSERT (case_id,order_id,order_line_id,quantity,status,warehouse_id), UPDATE (status)
  ON order_line_refund_admissions TO order_runtime;
GRANT INSERT (package_id,order_id,merchant_id,carrier,tracking_number,warehouse_id),
  UPDATE (warehouse_receipt_at,voided_at,posted_at) ON order_package_allocations TO order_runtime;
GRANT INSERT (package_id,order_line_id,quantity), UPDATE (actual_quantity) ON order_package_lines TO order_runtime;

GRANT INSERT (message_id,message_kind,payload_fingerprint,processing_status),
  UPDATE (processing_status,response_payload,error_detail,processed_at) ON order_remote_message_inbox TO order_runtime;
GRANT INSERT (process_id,order_id,process_type,status,current_step,waiting_for,command_id,command_fingerprint,correlation_id,
    causation_id,traceparent,attempts,result_deadline_at,next_check_at,version,created_at,updated_at),
  UPDATE (status,current_step,waiting_for,attempts,last_error,result_deadline_at,next_check_at,
    recovered_by,recovery_reason,recovered_at,updated_at,completed_at) ON order_process_managers TO order_runtime;
GRANT INSERT (command_id,process_id,order_id,target_service,command_type,payload_fingerprint,payload,status,
    attempts,next_attempt_at,result_deadline_at,correlation_id,causation_id,traceparent,created_at),
  UPDATE (status,attempts,next_attempt_at,last_error,acknowledged_at) ON order_command_outbox TO order_runtime;
GRANT INSERT (result_id,process_id,order_id,result_type,outcome,payload_fingerprint,payload,processing_status,error_detail,
    correlation_id,causation_id,traceparent,received_at,processed_at),
  UPDATE (processing_status,error_detail) ON order_result_inbox TO order_runtime;

GRANT SELECT, INSERT (request_id,source_service,content_fingerprint,expires_at), DELETE
  ON order_internal_request_replay TO order_runtime;
ALTER TABLE order_internal_request_replay ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS order_runtime_replay_read ON order_internal_request_replay;
DROP POLICY IF EXISTS order_runtime_replay_insert ON order_internal_request_replay;
DROP POLICY IF EXISTS order_runtime_replay_expire ON order_internal_request_replay;
CREATE POLICY order_runtime_replay_read ON order_internal_request_replay FOR SELECT TO order_runtime USING (true);
CREATE POLICY order_runtime_replay_insert ON order_internal_request_replay FOR INSERT TO order_runtime WITH CHECK (true);
CREATE POLICY order_runtime_replay_expire ON order_internal_request_replay FOR DELETE TO order_runtime
  USING (expires_at < statement_timestamp());
