GRANT SELECT ON logistics_platform.shipment_records, logistics_platform.shipment_events,
  logistics_platform.callback_outbox, logistics_platform.callback_outbox_events TO logistics_runtime;
GRANT INSERT (merchant_shipment_no,platform_shipment_no,order_number,carrier,tracking_number,status,callback_url),
      UPDATE (resume_status,status,updated_at,carrier,tracking_number,shipment_version,test_callback_result)
  ON logistics_platform.shipment_records TO logistics_runtime;
GRANT INSERT (shipment_id,event_id,event_type,status,location,description,occurred_at)
  ON logistics_platform.shipment_events TO logistics_runtime;
GRANT INSERT (app_id,nonce,request_timestamp) ON logistics_platform.signed_request_nonces TO logistics_runtime;
GRANT INSERT (shipment_id,event_id,callback_url,payload,max_attempts),
      UPDATE (status,attempts,max_attempts,next_attempt_at,lease_until,claimed_by,last_error,updated_at,published_at)
  ON logistics_platform.callback_outbox TO logistics_runtime;
GRANT INSERT (outbox_id,event_type,from_status,to_status,operated_by,reason)
  ON logistics_platform.callback_outbox_events TO logistics_runtime;
