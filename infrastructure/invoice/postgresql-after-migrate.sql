GRANT SELECT ON invoice_platform.invoice_requests, invoice_platform.invoice_tickets,
  invoice_platform.invoice_receipts TO invoice_runtime;

GRANT INSERT (request_id,kind,reference_no,payment_no,original_invoice_no,amount,currency,simulation,status),
      UPDATE (status,accepted_at,process_after,invoice_no,failure_code,completed_at)
  ON invoice_platform.invoice_requests TO invoice_runtime;

GRANT INSERT (invoice_no,request_id,kind,original_invoice_no,amount,currency)
  ON invoice_platform.invoice_tickets TO invoice_runtime;

GRANT INSERT (event_id,request_id), UPDATE (attempts,next_attempt_at,delivered_at)
  ON invoice_platform.invoice_receipts TO invoice_runtime;
