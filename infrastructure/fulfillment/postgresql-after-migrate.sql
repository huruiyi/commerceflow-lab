GRANT SELECT ON fulfillment_runtime_mode, fulfillment_shadow_quarantine, fulfillment_command_ids,
  fulfillment_shipment_jobs, fulfillment_merchant_jobs, fulfillment_shipment_recoveries,
  fulfillment_merchant_recoveries TO fulfillment_runtime;

-- PostgreSQL requires UPDATE on at least one column for SELECT FOR UPDATE.
GRANT UPDATE (singleton_id) ON fulfillment_runtime_mode TO fulfillment_runtime;
GRANT INSERT (command_id,order_id), UPDATE (command_id) ON fulfillment_command_ids TO fulfillment_runtime;
GRANT SELECT, INSERT (order_id), UPDATE (order_id) ON fulfillment_order_locks TO fulfillment_runtime;

GRANT INSERT (command_id,order_id,merchant_id,order_number,carrier,tracking_number,step,status,
      next_attempt_at,package_lines,warehouse_id),
      UPDATE (step,status,attempts,owner_token,lease_until,next_attempt_at,last_error,updated_at)
ON fulfillment_shipment_jobs TO fulfillment_runtime;

GRANT INSERT (command_id,order_id,merchant_id,operation,expected_version,carrier,tracking_number,detail,step,status,
      next_attempt_at),
      UPDATE (step,status,attempts,owner_token,lease_until,next_attempt_at,last_error,updated_at)
ON fulfillment_merchant_jobs TO fulfillment_runtime;

GRANT INSERT (order_id,command_id,recovery_id,operator_id,step,previous_error,reason,evidence)
ON fulfillment_shipment_recoveries TO fulfillment_runtime;

GRANT INSERT (command_id,recovery_id,operator_id,step,previous_error,reason,evidence)
ON fulfillment_merchant_recoveries TO fulfillment_runtime;
